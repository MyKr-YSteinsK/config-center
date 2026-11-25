package com.example.configcenter.receipt;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("mysql")
@DirtiesContext
class MysqlWriteReceiptIT extends WriteReceiptScenarios {}
