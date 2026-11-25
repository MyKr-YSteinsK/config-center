package com.example.configcenter.receipt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

// JPA creates the H2 table and validates MySQL. The receipt store executes the claim with JDBC
// so duplicate-key errors can be handled at that statement without logging the key or the result.
@Entity
@Table(name = "write_receipt", uniqueConstraints = @UniqueConstraint(
        name = "uk_write_receipt_scope_key", columnNames = {"app", "env", "request_key"}))
public class WriteReceipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String app;

    @Column(nullable = false, length = 50)
    private String env;

    @Column(name = "request_key", nullable = false, length = 128)
    private byte[] requestKey;

    @Column(nullable = false, length = 32)
    private String operation;

    @Column(nullable = false)
    private short intentVersion;

    @Column(nullable = false, length = 64)
    private String intentDigest;

    @Column(nullable = false)
    private short snapshotVersion;

    @Column(columnDefinition = "TEXT")
    private String resultJson;

    @Column(nullable = false)
    private Instant createdAt;
}
