package com.ecommerce.paymentservice.controller;

import com.ecommerce.paymentservice.TestTokens;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentMethod;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.repository.OutboxEventRepository;
import com.ecommerce.paymentservice.repository.CourierReceiptRepository;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import com.ecommerce.paymentservice.service.storage.ObjectStorage;
import com.ecommerce.paymentservice.service.storage.ObjectStorage.StoredFile;
import com.ecommerce.paymentservice.service.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Not @Transactional: the receipt and its events must really commit. RabbitMQ and the file storage are faked. */
@SpringBootTest
@AutoConfigureMockMvc
class CourierReceiptsTest {

    private static final String URL = "/api/v1/payment/admin/courier-receipts";
    private static final byte[] PDF = "%PDF-1.7 fake receipt".getBytes();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PaymentRepository payments;
    @Autowired
    CourierReceiptRepository receipts;

    @MockitoBean
    RabbitTemplate rabbit;

    @Autowired
    OutboxEventRepository outbox;

    @BeforeEach
    void emptyTheOutbox() {
        // Events of other tests that were never sent (there was no RabbitMQ mock) must not be sent during this test.
        outbox.deleteAll();
    }
    @MockitoBean
    ObjectStorage storage;

    @AfterEach
    void clean() {
        payments.deleteAll();
        receipts.deleteAll();
    }

    private int counter = 5000;

    private Payment payment(String reference, String courier, String tracking, PaymentStatus status, String amount) {
        Payment payment = new Payment();
        payment.setPaymentNo("PAY-" + (++counter) + "-" + reference.hashCode() % 1000);
        payment.setReference(reference);
        payment.setMethod(PaymentMethod.COD);
        payment.setAmount(new BigDecimal(amount));
        payment.setCurrency("LKR");
        payment.setStatus(status);
        payment.setCourier(courier);
        payment.setTrackingNumber(tracking);
        return payments.save(payment);
    }

    private ResultActions enter(String courier, String receiptNo, String amountReceived, String trackings, MockMultipartFile file) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(URL)
                .param("courier", courier).param("receiptNo", receiptNo).param("bankReference", "BANK-77")
                .param("receivedOn", "2026-10-20").param("amountReceived", amountReceived)
                .param("trackingNumbers", trackings.split(","))
                .cookie(TestTokens.adminCookie());
        if (file != null) {
            request.file(file);
        }
        return mockMvc.perform(request);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "r41.pdf", "application/pdf", PDF);
    }

    private void twoCollected() {
        payment("MX-1", "Courier A", "WB1", PaymentStatus.COLLECTED, "9500.00");
        payment("MX-2", "Courier A", "WB2", PaymentStatus.COLLECTED, "3100.00");
    }

    private PaymentStatus statusOf(String reference) {
        return payments.findAll().stream().filter(p -> p.getReference().equals(reference)).findFirst().orElseThrow().getStatus();
    }

    // ---------- the happy path ----------

    @Test
    void adminEntersAReceiptAndTheParcelsBecomePaid() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "11950.00", "WB1,WB2", pdf())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt.receiptNo").value("R-41"))
                .andExpect(jsonPath("$.receipt.collectedTotal").value(12600.00))
                .andExpect(jsonPath("$.receipt.fees").value(650.00))
                .andExpect(jsonPath("$.receipt.amountReceived").value(11950.00))
                .andExpect(jsonPath("$.receipt.paymentCount").value(2))
                .andExpect(jsonPath("$.receipt.createdBy").value(1))
                .andExpect(jsonPath("$.payments", hasSize(2)))
                .andExpect(jsonPath("$.payments[0].status").value("PAID"))
                .andExpect(jsonPath("$.payments[0].courierReceiptNo").value("R-41"));

        assertThat(statusOf("MX-1")).isEqualTo(PaymentStatus.PAID);
        assertThat(statusOf("MX-2")).isEqualTo(PaymentStatus.PAID);
        assertThat(receipts.count()).isEqualTo(1);
    }

    @Test
    void oneSucceededEventPerPaymentAfterTheCommit() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "12600.00", "WB1,WB2", pdf()).andExpect(status().isCreated());

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(rabbit, times(2)).convertAndSend(eq("app.events"), eq("payment.succeeded"), body.capture(), any(MessagePostProcessor.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) body.getAllValues().get(0);
        assertThat(event).containsEntry("courierReceiptNo", "R-41").containsEntry("currency", "LKR");
        assertThat(event.get("reference")).isIn("MX-1", "MX-2");
        assertThat(event.get("paidAt")).isNotNull();
    }

    @Test
    void theProofFileIsStoredInThePrivateBucket() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "12600.00", "WB1,WB2", pdf()).andExpect(status().isCreated());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq(PDF), eq("application/pdf"));
        assertThat(key.getValue()).matches("[0-9a-f-]{36}\\.pdf");
        assertThat(receipts.findAll().get(0).getFileKey()).isEqualTo(key.getValue());
    }

    @Test
    void courierNameAndRepeatedTrackingNumbersAreCleaned() throws Exception {
        twoCollected();

        enter("courier a", "R-41", "12600.00", "WB1,WB1, WB2 ", pdf())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt.courier").value("Courier A"))
                .andExpect(jsonPath("$.receipt.paymentCount").value(2));
    }

    // ---------- every wrong tracking number is reported, nothing is saved ----------

    @Test
    void anUnknownTrackingNumberIsRefused() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "100.00", "WB1,NOPE", pdf())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRACKING_NUMBER_UNKNOWN"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.startsWith("NOPE")));

        assertThat(statusOf("MX-1")).as("nothing is saved when one number is wrong").isEqualTo(PaymentStatus.COLLECTED);
        assertThat(receipts.count()).isZero();
        verify(storage, never()).put(anyString(), any(), anyString());
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
    }

    @Test
    void aParcelThatIsStillOnTheRoadMustBeMarkedDeliveredFirst() throws Exception {
        payment("MX-1", "Courier A", "WB1", PaymentStatus.COLLECTED, "9500.00");
        payment("MX-3", "Courier A", "WB3", PaymentStatus.PENDING, "100.00");

        enter("Courier A", "R-41", "9500.00", "WB1,WB3", pdf())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_COLLECTED"))
                .andExpect(jsonPath("$.details[0]").value("WB3: mark this parcel delivered first. The payment is still PENDING."));
    }

    @Test
    void aTrackingNumberOfAnotherCourierIsRefused() throws Exception {
        payment("MX-1", "Courier B", "WB1", PaymentStatus.COLLECTED, "9500.00");

        enter("Courier A", "R-41", "9500.00", "WB1", pdf())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_COURIER"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("Courier B")));
    }

    @Test
    void aPaymentThatIsAlreadyPaidCannotBePaidTwice() throws Exception {
        twoCollected();
        enter("Courier A", "R-41", "12600.00", "WB1,WB2", pdf()).andExpect(status().isCreated());

        enter("Courier A", "R-42", "9500.00", "WB1", pdf())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_COLLECTED"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("already paid")));
    }

    @Test
    void allProblemsAreListedAtOnce() throws Exception {
        payment("MX-1", "Courier B", "WB1", PaymentStatus.COLLECTED, "1.00");
        payment("MX-3", "Courier A", "WB3", PaymentStatus.PENDING, "1.00");

        enter("Courier A", "R-41", "1.00", "WB1,WB3,GONE", pdf())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRONG_COURIER"))
                .andExpect(jsonPath("$.details", hasSize(3)));
    }

    @Test
    void theSameReceiptCannotBeEnteredTwice() throws Exception {
        twoCollected();
        payment("MX-3", "Courier A", "WB3", PaymentStatus.COLLECTED, "500.00");
        enter("Courier A", "R-41", "12600.00", "WB1,WB2", pdf()).andExpect(status().isCreated());

        enter("courier a", "R-41", "500.00", "WB3", pdf())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_ALREADY_ENTERED"));
        assertThat(statusOf("MX-3")).isEqualTo(PaymentStatus.COLLECTED);
    }

    // ---------- bad input ----------

    @Test
    void theProofFileIsRequired() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "12600.00", "WB1,WB2", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        assertThat(receipts.count()).isZero();
    }

    @Test
    void aTextFileCalledPdfIsRefused() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "12600.00", "WB1,WB2", new MockMultipartFile("file", "r.pdf", "application/pdf", "not a pdf".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        verify(storage, never()).put(anyString(), any(), anyString());
    }

    @Test
    void moneyReceivedCannotBeMoreThanCollected() throws Exception {
        twoCollected();

        enter("Courier A", "R-41", "99999.00", "WB1,WB2", pdf())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(statusOf("MX-1")).isEqualTo(PaymentStatus.COLLECTED);
        assertThat(receipts.count()).isZero();
        verify(storage).delete(anyString()); // the file stored for this try is removed again
    }

    @Test
    void badFieldsAreRefused() throws Exception {
        twoCollected();

        enter("Pigeon Post", "R-41", "1.00", "WB1", pdf()).andExpect(status().isBadRequest());
        enter("Courier A", "R 41; drop", "1.00", "WB1", pdf()).andExpect(status().isBadRequest());
        enter("Courier A", "R-41", "1.005", "WB1", pdf()).andExpect(status().isBadRequest());
        enter("Courier A", "R-41", "-1.00", "WB1", pdf()).andExpect(status().isBadRequest());
        enter("Courier A", "R-41", "1.00", " , ", pdf()).andExpect(status().isBadRequest());
    }

    @Test
    void whenTheFileStorageIsDownNothingIsSaved() throws Exception {
        twoCollected();
        doThrow(new StorageException("down", null)).when(storage).put(anyString(), any(), anyString());

        enter("Courier A", "R-41", "12600.00", "WB1,WB2", pdf()).andExpect(status().isServiceUnavailable());

        assertThat(statusOf("MX-1")).isEqualTo(PaymentStatus.COLLECTED);
        assertThat(receipts.count()).isZero();
    }

    // ---------- read ----------

    @Test
    void adminListsReadsAndDownloadsReceipts() throws Exception {
        twoCollected();
        enter("Courier A", "R-41", "11950.00", "WB1,WB2", pdf()).andExpect(status().isCreated());
        var receipt = receipts.findAll().get(0);
        when(storage.get(receipt.getFileKey())).thenReturn(Optional.of(new StoredFile(PDF, "application/pdf")));

        mockMvc.perform(get(URL).cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].paymentCount").value(2));
        mockMvc.perform(get(URL + "?courier=courier b").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(URL + "/" + receipt.getId()).cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.receipt.receiptNo").value("R-41"))
                .andExpect(jsonPath("$.payments", hasSize(2)));
        mockMvc.perform(get(URL + "/" + receipt.getId() + "/file").cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PDF));
        mockMvc.perform(get(URL + "/" + java.util.UUID.randomUUID()).cookie(TestTokens.adminCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsMayUseTheReceiptUrls() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).cookie(TestTokens.userCookie(7)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(multipart(URL).file(pdf()).cookie(TestTokens.userCookie(7))).andExpect(status().isForbidden());
    }
}
