package com.ecommerce.paymentservice.controller;

import com.ecommerce.paymentservice.TestTokens;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.entity.PaymentMethod;
import com.ecommerce.paymentservice.entity.PaymentStatus;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminPaymentsTest {

    private static final String URL = "/api/v1/payment/admin/payments";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PaymentRepository payments;

    @AfterEach
    void clean() {
        payments.deleteAll();
    }

    private void payment(String no, String reference, String courier, String tracking, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setPaymentNo(no);
        payment.setReference(reference);
        payment.setMethod(PaymentMethod.COD);
        payment.setAmount(new BigDecimal("9500.00"));
        payment.setCurrency("LKR");
        payment.setStatus(status);
        payment.setCourier(courier);
        payment.setTrackingNumber(tracking);
        payments.save(payment);
    }

    private void data() {
        payment("PAY-5001", "MX-10001", null, null, PaymentStatus.PENDING);
        payment("PAY-5002", "MX-10002", "Courier A", "WB55310", PaymentStatus.COLLECTED);
        payment("PAY-5003", "MX-10003", "Courier B", "XY99", PaymentStatus.COLLECTED);
    }

    @Test
    void listsAllPaymentsWithTheirFields() throws Exception {
        data();

        mockMvc.perform(get(URL).cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].method").value("COD"))
                .andExpect(jsonPath("$.content[0].amount").value(9500.00))
                .andExpect(jsonPath("$.content[0].currency").value("LKR"));
    }

    @Test
    void filtersByStatusCourierReferenceAndTrackingNumber() throws Exception {
        data();

        mockMvc.perform(get(URL + "?status=PENDING").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].paymentNo", contains("PAY-5001")));
        mockMvc.perform(get(URL + "?status=COLLECTED&courier=courier b").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].paymentNo", contains("PAY-5003")));
        mockMvc.perform(get(URL + "?reference=10002").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].paymentNo", contains("PAY-5002")));
        mockMvc.perform(get(URL + "?trackingNumber=wb553").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content[*].paymentNo", contains("PAY-5002")));
        mockMvc.perform(get(URL + "?reference=%25").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void pagesAreLimited() throws Exception {
        data();

        mockMvc.perform(get(URL + "?size=2&page=1").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get(URL + "?size=100000").cookie(TestTokens.adminCookie()))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void oneByPaymentNumberOr404() throws Exception {
        data();

        mockMvc.perform(get(URL + "/PAY-5002").cookie(TestTokens.adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("MX-10002"))
                .andExpect(jsonPath("$.trackingNumber").value("WB55310"));
        mockMvc.perform(get(URL + "/PAY-9999").cookie(TestTokens.adminCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aBadStatusIs400AndOnlyAdminsMayRead() throws Exception {
        mockMvc.perform(get(URL + "?status=BOGUS").cookie(TestTokens.adminCookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).cookie(TestTokens.userCookie(7))).andExpect(status().isForbidden());
    }
}
