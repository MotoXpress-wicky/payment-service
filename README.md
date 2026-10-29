# payment-service

Payments and **courier receipts**. Cash on delivery only (M2). Port **8084**. Database: `payment_db`.
Design: `infrastructure/docs/mvp/05-components/payment-service.md`.

This service is **domain-free**: it says `reference`, never the name of the shop thing it is paid for.
Every URL needs `ROLE_ADMIN`. Customers never call it.

## How money moves

```
event heard            payment change                 event published
---------------------  -----------------------------  -----------------
placed                 new payment, PENDING
shipped                save courier + tracking number
delivered              PENDING  -> COLLECTED          payment.collected
returned / cancelled   PENDING  -> CANCELLED          payment.cancelled
courier receipt saved  COLLECTED -> PAID              payment.succeeded (one per payment)
```

Which event name starts which action is set in `application.yaml` under `events.listen`. The Java code only knows
the generic actions `CREATE`, `SET_SHIPMENT`, `COLLECT` and `CANCEL`.
A message that arrives twice does nothing the second time (`processed_messages`). A move that the state diagram
does not allow is ignored and logged. A message that can never work (no `reference`, unknown reference, bad `total`)
goes to the dead-letter queue `payment.order-events.dlq`; other failures are tried 3 times first.

## API

| Method | Path | Does |
|---|---|---|
| GET | `/api/v1/payment/admin/payments` | List. Filters: `status`, `courier`, `reference`, `trackingNumber`, `page`, `size` |
| GET | `/api/v1/payment/admin/payments/{paymentNo}` | One payment |
| GET | `/api/v1/payment/admin/courier-receipts` | List receipts (filter `courier`) |
| POST | `/api/v1/payment/admin/courier-receipts` | New receipt (multipart, below) |
| GET | `/api/v1/payment/admin/courier-receipts/{id}` | One receipt with its payments |
| GET | `/api/v1/payment/admin/courier-receipts/{id}/file` | The PDF or photo (private bucket `courier-receipts`) |

New receipt, a multipart form:

| Field | Example |
|---|---|
| `courier` | `Courier A` (must be in `payments.couriers`) |
| `receiptNo` | `R-41` (unique per courier) |
| `bankReference` | optional |
| `receivedOn` | `2026-10-20` |
| `amountReceived` | `11950.00` (not more than the collected total) |
| `trackingNumbers` | `WB55310` repeated, or `WB55310,WB55311` |
| `file` | the PDF or photo. **Required**: no receipt without its proof |

What happens: every tracking number must exist, belong to that courier and be `COLLECTED`. If not, **nothing is saved**
and the answer lists every problem (`TRACKING_NUMBER_UNKNOWN`, `WRONG_COURIER`, `PAYMENT_NOT_COLLECTED`, for example
"mark WB55312 delivered first"). If all are good, the receipt and the `PAID` payments are saved in one transaction,
`fees = collected total - amount received`, and one `payment.succeeded` event per payment is published after the commit.

Error codes (shared 7-field JSON): `RECEIPT_ALREADY_ENTERED`, `TRACKING_NUMBER_UNKNOWN`, `WRONG_COURIER`,
`PAYMENT_NOT_COLLECTED`, `INVALID_FILE`, `NOT_FOUND`, `VALIDATION_FAILED`, `ACCESS_DENIED` and the token codes.

## Settings

| Setting | Env name | Meaning |
|---|---|---|
| `spring.datasource.*` | `DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Its own database |
| `spring.rabbitmq.*` | `SPRING_RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD` | RabbitMQ |
| `auth.token.secret` | `JWT_SECRET` | Same value as user-service |
| `storage.s3.*` | `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET` | Object storage |
| `payments.*`, `events.*` | (in `application.yaml`) | Payment number prefix, couriers, page sizes, file limit, queue and event list |

Profiles: `dev` (default, IntelliJ, helpers on `localhost`), `docker`, `prod`.

## Run and test

```bash
./mvnw test                 # all tests, on H2, no Docker needed
./mvnw spring-boot:run      # needs: docker compose up -d postgres rabbitmq object-storage
```

Check: http://localhost:8084/actuator/health shows `{"status":"UP"}`.
