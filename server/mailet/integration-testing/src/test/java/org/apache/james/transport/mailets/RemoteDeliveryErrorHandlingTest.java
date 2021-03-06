/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.transport.mailets;

import static io.restassured.RestAssured.given;
import static org.apache.james.mailets.configuration.Constants.DEFAULT_DOMAIN;
import static org.apache.james.mailets.configuration.Constants.PASSWORD;
import static org.apache.james.mailets.configuration.Constants.awaitAtMostOneMinute;
import static org.apache.james.mailets.configuration.MailetConfiguration.BCC_STRIPPER;
import static org.apache.james.mailets.configuration.ProcessorConfiguration.TRANSPORT_PROCESSOR;
import static org.apache.james.mock.smtp.server.ConfigurationClient.BehaviorsParamsBuilder.ConditionStep.anyInput;
import static org.apache.james.mock.smtp.server.ConfigurationClient.BehaviorsParamsBuilder.ResponseStep.doesNotAcceptAnyMail;
import static org.apache.james.mock.smtp.server.ConfigurationClient.BehaviorsParamsBuilder.ResponseStep.serviceNotAvailable;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.apache.james.MemoryJamesServerMain;
import org.apache.james.core.Domain;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.dnsservice.api.InMemoryDNSService;
import org.apache.james.mailets.TemporaryJamesServer;
import org.apache.james.mailets.configuration.MailetConfiguration;
import org.apache.james.mailets.configuration.ProcessorConfiguration;
import org.apache.james.mailrepository.api.MailRepositoryUrl;
import org.apache.james.mock.smtp.server.model.SMTPCommand;
import org.apache.james.mock.smtp.server.testing.MockSmtpServerExtension;
import org.apache.james.mock.smtp.server.testing.MockSmtpServerExtension.DockerMockSmtp;
import org.apache.james.modules.protocols.SmtpGuiceProbe;
import org.apache.james.queue.api.MailQueueFactory;
import org.apache.james.transport.matchers.All;
import org.apache.james.utils.DataProbeImpl;
import org.apache.james.utils.MailRepositoryProbeImpl;
import org.apache.james.utils.SMTPMessageSender;
import org.apache.james.utils.SMTPMessageSenderExtension;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.apache.james.webadmin.WebAdminUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.util.Modules;

import io.restassured.specification.RequestSpecification;

public class RemoteDeliveryErrorHandlingTest {
    private static final String FROM = "from@" + DEFAULT_DOMAIN;
    private static final String RECIPIENT_DOMAIN = "test.com";
    private static final String RECIPIENT = "touser@" + RECIPIENT_DOMAIN;
    private static final String LOCALHOST = "localhost";
    private static final MailRepositoryUrl REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY = MailRepositoryUrl.from("memory://var/mail/error/remote-delivery/temporary");
    private static final MailRepositoryUrl REMOTE_DELIVERY_PERMANENT_ERROR_REPOSITORY = MailRepositoryUrl.from("memory://var/mail/error/remote-delivery/permanent");

    @RegisterExtension
    static MockSmtpServerExtension mockSmtpServerExtension = new MockSmtpServerExtension();

    @TempDir
    static File tempDir;

    @RegisterExtension
    SMTPMessageSenderExtension smtpSenderExtension = new SMTPMessageSenderExtension(Domain.of(DEFAULT_DOMAIN));

    private TemporaryJamesServer jamesServer;
    private RequestSpecification webAdminApi;

    @BeforeEach
    void setup(DockerMockSmtp dockerMockSmtp) throws Exception {
        InMemoryDNSService inMemoryDNSService = new InMemoryDNSService()
            .registerMxRecord(RECIPIENT_DOMAIN, dockerMockSmtp.getIPAddress());

        jamesServer = TemporaryJamesServer.builder()
            .withBase(Modules.combine(MemoryJamesServerMain.SMTP_ONLY_MODULE, MemoryJamesServerMain.WEBADMIN_TESTING))
            .withOverrides(binder -> binder.bind(DNSService.class).toInstance(inMemoryDNSService))
            .withMailetContainer(TemporaryJamesServer.simpleMailetContainerConfiguration()
                .putProcessor(ProcessorConfiguration.transport()
                    .addMailet(BCC_STRIPPER)
                    .addMailet(MailetConfiguration.builder()
                        .mailet(RemoteDelivery.class)
                        .addProperty("maxRetries", "1")
                        .addProperty("delayTime", "0")
                        .addProperty("bounceProcessor", "remote-delivery-error")
                        .matcher(All.class)))
                .putProcessor(ProcessorConfiguration.builder()
                    .state("remote-delivery-error")
                    .addMailet(MailetConfiguration.builder()
                        .matcher(All.class)
                        .mailet(ToRepository.class)
                        .addProperty("repositoryPath", REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY.asString()))))
            .build(tempDir);

        jamesServer.start();

        jamesServer.getProbe(DataProbeImpl.class)
            .fluent()
            .addDomain(DEFAULT_DOMAIN)
            .addUser(FROM, PASSWORD);

        webAdminApi = WebAdminUtils.spec(jamesServer.getProbe(WebAdminGuiceProbe.class).getWebAdminPort());
    }

    @AfterEach
    void tearDown() {
        jamesServer.shutdown();
    }

    @Test
    void remoteDeliveryShouldStoreTemporaryFailures(SMTPMessageSender smtpMessageSender, DockerMockSmtp dockerMockSmtp) throws Exception {
        // Given a temporary failing remote server
        dockerMockSmtp.getConfigurationClient()
            .addNewBehavior()
            .expect(SMTPCommand.RCPT_TO)
            .matching(anyInput())
            .thenRespond(serviceNotAvailable("mock_response"))
            .anyTimes()
            .post();

        // When we relay a mail to this server
        smtpMessageSender.connect(LOCALHOST, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(FROM, PASSWORD)
            .sendMessage(FROM, RECIPIENT);

        // Then the mail is stored in REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY
        awaitAtMostOneMinute
            .untilAsserted(() -> assertThat(jamesServer.getProbe(MailRepositoryProbeImpl.class)
                .getRepositoryMailCount(REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY))
                .isEqualTo(1));
    }

    @Test
    void reprocessedTemporaryFailuresShouldEventuallySucceed(SMTPMessageSender smtpMessageSender, DockerMockSmtp dockerMockSmtp) throws Exception {
        // Given a failed remote delivery
        dockerMockSmtp.getConfigurationClient()
            .addNewBehavior()
            .expect(SMTPCommand.RCPT_TO)
            .matching(anyInput())
            .thenRespond(serviceNotAvailable("mock_response"))
            .anyTimes()
            .post();
        smtpMessageSender.connect(LOCALHOST, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(FROM, PASSWORD)
            .sendMessage(FROM, RECIPIENT);
        awaitAtMostOneMinute
            .untilAsserted(() -> assertThat(jamesServer.getProbe(MailRepositoryProbeImpl.class)
                .getRepositoryMailCount(REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY))
                .isEqualTo(1));

        // When the temporary error disappears
        dockerMockSmtp.getConfigurationClient()
            .clearBehaviors();

        // Then we can reprocess the mail
        given()
            .spec(webAdminApi)
            .param("action", "reprocess")
            .param("queue", MailQueueFactory.SPOOL.asString())
            .param("processor", TRANSPORT_PROCESSOR)
            .patch("/mailRepositories/" + REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY.getPath().urlEncoded() + "/mails");
        awaitAtMostOneMinute.untilAsserted(() -> assertThat(dockerMockSmtp.getConfigurationClient()
                .listMails())
            .hasSize(1));
    }

    @Test
    @Disabled("Remote delivery should attach failures information to the mail, and we should provide a Matcher for it")
    void remoteDeliveryShouldStorePermanentFailuresSeparately(SMTPMessageSender smtpMessageSender, DockerMockSmtp dockerMockSmtp) throws Exception {
        // Given a permanent failing remote server
        dockerMockSmtp.getConfigurationClient()
            .addNewBehavior()
            .expect(SMTPCommand.RCPT_TO)
            .matching(anyInput())
            .thenRespond(doesNotAcceptAnyMail("mock_response"))
            .anyTimes()
            .post();

        // When we relay a mail to it
        smtpMessageSender.connect(LOCALHOST, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(FROM, PASSWORD)
            .sendMessage(FROM, RECIPIENT);

        // Then mail should be stored in permanent error repository
        awaitAtMostOneMinute
            .untilAsserted(() -> assertThat(jamesServer.getProbe(MailRepositoryProbeImpl.class)
                .getRepositoryMailCount(REMOTE_DELIVERY_PERMANENT_ERROR_REPOSITORY))
                .isEqualTo(1));
    }

    @Test
    @Disabled("We need to count retries")
    void remoteDeliveryShouldStoreTemporaryFailureAsPermanentWhenExceedsMaximumRetries(SMTPMessageSender smtpMessageSender, DockerMockSmtp dockerMockSmtp) throws Exception {
        // Given a failed remote delivery
        dockerMockSmtp.getConfigurationClient()
            .addNewBehavior()
            .expect(SMTPCommand.RCPT_TO)
            .matching(anyInput())
            .thenRespond(serviceNotAvailable("mock_response"))
            .anyTimes()
            .post();
        smtpMessageSender.connect(LOCALHOST, jamesServer.getProbe(SmtpGuiceProbe.class).getSmtpPort())
            .authenticate(FROM, PASSWORD)
            .sendMessage(FROM, RECIPIENT);
        awaitAtMostOneMinute
            .untilAsserted(() -> assertThat(jamesServer.getProbe(MailRepositoryProbeImpl.class)
                .getRepositoryMailCount(REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY))
                .isEqualTo(1));

        // When we retry and temporary problem is not solved
        given()
            .spec(webAdminApi)
            .param("action", "reprocess")
            .param("queue", MailQueueFactory.SPOOL.asString())
            .param("processor", TRANSPORT_PROCESSOR)
            .patch("/mailRepositories/" + REMOTE_DELIVERY_TEMPORARY_ERROR_REPOSITORY.getPath().urlEncoded() + "/mails");

        // Then mail should be stored in permanent error repository
        awaitAtMostOneMinute
            .untilAsserted(() -> assertThat(jamesServer.getProbe(MailRepositoryProbeImpl.class)
                .getRepositoryMailCount(REMOTE_DELIVERY_PERMANENT_ERROR_REPOSITORY))
                .isEqualTo(1));
    }
}
