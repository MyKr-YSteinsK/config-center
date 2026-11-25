package com.example.configcenter.receipt;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DirtiesContext
class H2WriteReceiptTest extends WriteReceiptScenarios {}
