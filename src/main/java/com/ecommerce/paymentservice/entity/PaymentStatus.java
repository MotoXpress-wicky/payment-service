package com.ecommerce.paymentservice.entity;

/** The statuses of a payment (docs/mvp/09-state-diagrams.md, diagram 2). */
public enum PaymentStatus {
    /** Waiting: the parcel is not delivered yet. */
    PENDING,
    /** The courier delivered the parcel and holds the cash. */
    COLLECTED,
    /** A courier receipt proves the money arrived. */
    PAID,
    /** No money will come (cancelled before shipping, or returned). */
    CANCELLED
}
