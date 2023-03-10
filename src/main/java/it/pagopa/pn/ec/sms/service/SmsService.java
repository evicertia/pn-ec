package it.pagopa.pn.ec.sms.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.messaging.listener.Acknowledgment;
import io.awspring.cloud.messaging.listener.SqsMessageDeletionPolicy;
import io.awspring.cloud.messaging.listener.annotation.SqsListener;
import it.pagopa.pn.ec.commons.configurationproperties.TransactionProcessConfigurationProperties;
import it.pagopa.pn.ec.commons.configurationproperties.sqs.NotificationTrackerSqsName;
import it.pagopa.pn.ec.commons.exception.sns.SnsSendException;
import it.pagopa.pn.ec.commons.exception.sqs.SqsPublishException;
import it.pagopa.pn.ec.commons.model.dto.NotificationTrackerQueueDto;
import it.pagopa.pn.ec.commons.model.pojo.PresaInCaricoInfo;
import it.pagopa.pn.ec.commons.rest.call.ec.gestorerepository.GestoreRepositoryCall;
import it.pagopa.pn.ec.commons.service.AuthService;
import it.pagopa.pn.ec.commons.service.PresaInCaricoService;
import it.pagopa.pn.ec.commons.service.SnsService;
import it.pagopa.pn.ec.commons.service.SqsService;
import it.pagopa.pn.ec.rest.v1.dto.*;
import it.pagopa.pn.ec.sms.configurationproperties.SmsSqsQueueName;
import it.pagopa.pn.ec.sms.model.pojo.SmsPresaInCaricoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static it.pagopa.pn.ec.commons.service.SnsService.DEFAULT_RETRY_STRATEGY;
import static it.pagopa.pn.ec.commons.utils.SqsUtils.logIncomingMessage;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalCourtesySmsRequest.QosEnum.BATCH;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalCourtesySmsRequest.QosEnum.INTERACTIVE;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.ChannelEnum.SMS;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.MessageContentTypeEnum.PLAIN;
import static java.time.OffsetDateTime.now;

@Service
@Slf4j
public class SmsService extends PresaInCaricoService {

    private final SqsService sqsService;
    private final SnsService snsService;
    private final GestoreRepositoryCall gestoreRepositoryCall;
    private final SmsSqsQueueName smsSqsQueueName;
    private final NotificationTrackerSqsName notificationTrackerSqsName;
    private final TransactionProcessConfigurationProperties transactionProcessConfigurationProperties;

    protected SmsService(AuthService authService, SqsService sqsService, SnsService snsService,
                         GestoreRepositoryCall gestoreRepositoryCall, NotificationTrackerSqsName notificationTrackerSqsName,
                         SmsSqsQueueName smsSqsQueueName,
                         TransactionProcessConfigurationProperties transactionProcessConfigurationProperties) throws IOException {
        super(authService, gestoreRepositoryCall);
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.gestoreRepositoryCall = gestoreRepositoryCall;
        this.notificationTrackerSqsName = notificationTrackerSqsName;
        this.smsSqsQueueName = smsSqsQueueName;
        this.transactionProcessConfigurationProperties = transactionProcessConfigurationProperties;
    }

    File file = new File("src/main/resources/commons/retryPolicy.json");
    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, List<BigDecimal>> retryPolicies;

    {
        try {
            retryPolicies = objectMapper.readValue(file, new TypeReference<Map<String, List<BigDecimal>>>() {});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Mono<Void> specificPresaInCarico(final PresaInCaricoInfo presaInCaricoInfo) {

        var smsPresaInCaricoInfo = (SmsPresaInCaricoInfo) presaInCaricoInfo;
        var digitalCourtesySmsRequest = smsPresaInCaricoInfo.getDigitalCourtesySmsRequest();
        digitalCourtesySmsRequest.setRequestId(presaInCaricoInfo.getRequestIdx());
        String xPagopaExtchCxId = presaInCaricoInfo.getXPagopaExtchCxId();

//      Insert request from SMS request and publish to Notification Tracker with next status -> BOOKED
        return insertRequestFromSms(digitalCourtesySmsRequest,
                                    xPagopaExtchCxId).then(sqsService.send(notificationTrackerSqsName.statoSmsName(),
                                                                           new NotificationTrackerQueueDto(presaInCaricoInfo.getRequestIdx(),
                                                                                                           presaInCaricoInfo.getXPagopaExtchCxId(),
                                                                                                           now(),
                                                                                                           transactionProcessConfigurationProperties.sms(),
                                                                                                           transactionProcessConfigurationProperties.smsStartStatus(),
                                                                                                           "booked",
                                                                                                           // TODO: SET
                                                                                                           // eventDetails
                                                                                                           "",
                                                                                                           null)))
//                                                            Publish to SMS INTERACTIVE or SMS BATCH
                                                     .flatMap(sendMessageResponse -> {
                                                         DigitalCourtesySmsRequest.QosEnum qos =
                                                                 smsPresaInCaricoInfo.getDigitalCourtesySmsRequest().getQos();
                                                         smsPresaInCaricoInfo.setStatusAfterStart("booked");
                                                         if (qos == INTERACTIVE) {
                                                             return sqsService.send(smsSqsQueueName.interactiveName(),
                                                                                    smsPresaInCaricoInfo);
                                                         } else if (qos == BATCH) {
                                                             return sqsService.send(smsSqsQueueName.batchName(), smsPresaInCaricoInfo);
                                                         } else {
                                                             return Mono.empty();
                                                         }
                                                     }).then();
    }

    @SuppressWarnings("Duplicates")
    private Mono<RequestDto> insertRequestFromSms(final DigitalCourtesySmsRequest digitalCourtesySmsRequest, String xPagopaExtchCxId) {
        return Mono.fromCallable(() -> {
            var requestDto = new RequestDto();

            requestDto.setRequestIdx(digitalCourtesySmsRequest.getRequestId());
            requestDto.setClientRequestTimeStamp(digitalCourtesySmsRequest.getClientRequestTimeStamp());
            requestDto.setxPagopaExtchCxId(xPagopaExtchCxId);

            var requestPersonalDto = new RequestPersonalDto();
            var digitalRequestPersonalDto = new DigitalRequestPersonalDto();
            digitalRequestPersonalDto.setQos(DigitalRequestPersonalDto.QosEnum.valueOf(digitalCourtesySmsRequest.getQos().name()));
            digitalRequestPersonalDto.setReceiverDigitalAddress(digitalCourtesySmsRequest.getReceiverDigitalAddress());
            digitalRequestPersonalDto.setMessageText(digitalCourtesySmsRequest.getMessageText());
            digitalRequestPersonalDto.setSenderDigitalAddress(digitalCourtesySmsRequest.getSenderDigitalAddress());
            digitalRequestPersonalDto.setSubjectText("");
            requestPersonalDto.setDigitalRequestPersonal(digitalRequestPersonalDto);

            var requestMetadataDto = new RequestMetadataDto();
            var digitalRequestMetadataDto = new DigitalRequestMetadataDto();
            digitalRequestMetadataDto.setCorrelationId(digitalCourtesySmsRequest.getCorrelationId());
            digitalRequestMetadataDto.setEventType(digitalCourtesySmsRequest.getEventType());
            digitalRequestMetadataDto.setTags(digitalCourtesySmsRequest.getTags());
            digitalRequestMetadataDto.setMessageContentType(PLAIN);
            digitalRequestMetadataDto.setChannel(SMS);
            requestMetadataDto.setDigitalRequestMetadata(digitalRequestMetadataDto);

            requestDto.setRequestPersonal(requestPersonalDto);
            requestDto.setRequestMetadata(requestMetadataDto);
            return requestDto;
        }).flatMap(gestoreRepositoryCall::insertRichiesta);
    }

    @SqsListener(value = "${sqs.queue.sms.interactive-name}", deletionPolicy = SqsMessageDeletionPolicy.NEVER)
    public void lavorazioneRichiesta(final SmsPresaInCaricoInfo smsPresaInCaricoInfo, final Acknowledgment acknowledgment) {

        log.info("<-- START LAVORAZIONE RICHIESTA SMS -->");
        logIncomingMessage(smsSqsQueueName.interactiveName(), smsPresaInCaricoInfo);

        var requestId = smsPresaInCaricoInfo.getRequestIdx();
        var clientId = smsPresaInCaricoInfo.getXPagopaExtchCxId();
        var digitalCourtesySmsRequest = smsPresaInCaricoInfo.getDigitalCourtesySmsRequest();

        AtomicReference<GeneratedMessageDto> generatedMessageDto = new AtomicReference<>();

//      Try to send SMS
        snsService.send(digitalCourtesySmsRequest.getReceiverDigitalAddress(), digitalCourtesySmsRequest.getMessageText())

//                The SMS in sent, publish to Notification Tracker with next status -> SENT
                  .flatMap(publishResponse -> {
                      generatedMessageDto.set(new GeneratedMessageDto().id(publishResponse.messageId()).system("systemPlaceholder"));
                      return sqsService.send(notificationTrackerSqsName.statoSmsName(),
                                             new NotificationTrackerQueueDto(requestId,
                                                                             clientId,
                                                                             now(),
                                                                             transactionProcessConfigurationProperties.sms(),
                                                                             smsPresaInCaricoInfo.getStatusAfterStart(),
                                                                             "sent",
                                                                             // TODO: SET eventDetails
                                                                             "",
                                                                             generatedMessageDto.get()));
                  })

//                Delete from queue
                  .doOnSuccess(result -> acknowledgment.acknowledge())

//                 An error occurred during SMS send, start retries
                  .onErrorResume(SnsSendException.class,
                                 snsSendException -> retrySmsSend(acknowledgment,
                                                                  smsPresaInCaricoInfo,
                                                                  smsPresaInCaricoInfo.getStatusAfterStart(),
                                                                  generatedMessageDto.get()))

//                An error occurred during SQS publishing to the Notification Tracker -> Publish to Errori SMS queue and
//                notify to retry update status only
//                TODO: CHANGE THE PAYLOAD
                  .onErrorResume(SqsPublishException.class,
                                 sqsPublishException -> sqsService.send(smsSqsQueueName.errorName(), smsPresaInCaricoInfo)).subscribe();
    }

    private Mono<SendMessageResponse> retrySmsSend(final Acknowledgment acknowledgment, final SmsPresaInCaricoInfo smsPresaInCaricoInfo,
                                                   final String currentStatus, final GeneratedMessageDto generateMessageDto) {

        var requestId = smsPresaInCaricoInfo.getRequestIdx();
        var clientId = smsPresaInCaricoInfo.getXPagopaExtchCxId();
        var digitalCourtesySmsRequest = smsPresaInCaricoInfo.getDigitalCourtesySmsRequest();

//      Try to send SMS
        return snsService.send(digitalCourtesySmsRequest.getReceiverDigitalAddress(), digitalCourtesySmsRequest.getMessageText())

//                       Retry to send SMS
                         .retryWhen(DEFAULT_RETRY_STRATEGY)

//                       The SMS in sent, publish to Notification Tracker with next status -> SENT
                         .flatMap(publishResponse -> sqsService.send(notificationTrackerSqsName.statoSmsName(),
                                                                     new NotificationTrackerQueueDto(requestId,
                                                                                                     clientId,
                                                                                                     now(),
                                                                                                     transactionProcessConfigurationProperties.sms(),
                                                                                                     currentStatus,
                                                                                                     "sent",
                                                                                                     // TODO: SET eventDetails
                                                                                                     "",
                                                                                                     new GeneratedMessageDto().id(
                                                                                                                                      publishResponse.messageId())
                                                                                                                              .system("systemPlaceholder"))))

//                       Delete from queue
                         .doOnSuccess(result -> acknowledgment.acknowledge())

//                       The maximum number of retries has ended
                         .onErrorResume(SnsSendException.SnsMaxRetriesExceededException.class,
                                        snsMaxRetriesExceeded -> smsRetriesExceeded(acknowledgment,
                                                                                    smsPresaInCaricoInfo,
                                                                                    generateMessageDto,
                                                                                    currentStatus));
    }

    private Mono<SendMessageResponse> smsRetriesExceeded(final Acknowledgment acknowledgment,
                                                         final SmsPresaInCaricoInfo smsPresaInCaricoInfo,
                                                         final GeneratedMessageDto generatedMessageDto, String currentStatus) {

        var requestId = smsPresaInCaricoInfo.getRequestIdx();
        var clientId = smsPresaInCaricoInfo.getXPagopaExtchCxId();

//      Publish to Notification Tracker with next status -> RETRY
        return sqsService.send(notificationTrackerSqsName.statoSmsName(),
                               new NotificationTrackerQueueDto(requestId,
                                                               clientId,
                                                               now(),
                                                               transactionProcessConfigurationProperties.sms(),
                                                               currentStatus,
                                                               "retry",
                                                               // TODO: SET eventDetails
                                                               "",
                                                               generatedMessageDto))

                         // Publish to ERRORI SMS queue
                         .then(sqsService.send(smsSqsQueueName.errorName(), smsPresaInCaricoInfo))

//                       Delete from queue
                         .doOnSuccess(result -> acknowledgment.acknowledge());
    }

    @SqsListener(value = "${sqs.queue.sms.error-name}", deletionPolicy = SqsMessageDeletionPolicy.NEVER)
    public void gestioneRetrySms(final SmsPresaInCaricoInfo smsPresaInCaricoInfo, final Acknowledgment acknowledgment) {

        log.info("<-- START GESTIONE ERRORI SMS -->");
        logIncomingMessage(smsSqsQueueName.errorName(), smsPresaInCaricoInfo);

        log.info(String.valueOf(retryPolicies.get(0).contains("SMS")));

        var requestId = smsPresaInCaricoInfo.getRequestIdx();
        var clientId = smsPresaInCaricoInfo.getXPagopaExtchCxId();
        var digitalCourtesySmsRequest = smsPresaInCaricoInfo.getDigitalCourtesySmsRequest();

        gestoreRepositoryCall.getRichiesta(requestId)
                .filter(requestDto -> !Objects.equals(requestDto.getStatusRequest(), "toDelete"))
                .map(requestDto -> {
                    if(requestDto.getRequestMetadata().getRetry().getRetryStep() == null) {
                        log.info("Primo tentativo di Retry");
                        requestDto.getRequestMetadata().getRetry().setRetryStep(BigDecimal.ZERO);
                        requestDto.getRequestMetadata().getRetry().setRetryPolicy(retryPolicies.get("SMS"));
                        requestDto.getRequestMetadata().getRetry().setLastRetryTimestamp(OffsetDateTime.now());
                    } else {
                        var retryNumber = requestDto.getRequestMetadata().getRetry().getRetryStep();
                        log.info(retryNumber + " tentativo di Retry");
                        requestDto.getRequestMetadata().getRetry().setRetryStep(retryNumber.add(BigDecimal.ONE));
                    }
                    return requestDto;
                })
                .filterWhen(requestDto -> {
                    var dateTime1 = requestDto.getRequestMetadata().getRetry().getLastRetryTimestamp();
                    var dateTime2 = OffsetDateTime.now();
                    Duration duration = Duration.between(dateTime1, dateTime2);
                    int step = requestDto.getRequestMetadata().getRetry().getRetryStep().intValueExact();
                    long minutes = duration.toMinutes();
                    long minutesToCheck = requestDto.getRequestMetadata().getRetry().getRetryPolicy().get(step).longValue();
                    if(minutes >= minutesToCheck || minutes > 40) {
                        return Mono.just(true);
                    } else {
                        return Mono.empty();
                    }
                })
                .flatMap(requestDto -> {
                    return snsService.send(digitalCourtesySmsRequest.getReceiverDigitalAddress(), digitalCourtesySmsRequest.getMessageText())
                            .flatMap(publishResponse -> {
                                var generatedMessageDto = new GeneratedMessageDto().id(publishResponse.messageId()).system("systemPlaceholder");
                                return sqsService.send(notificationTrackerSqsName.statoSmsName(),
                                        new NotificationTrackerQueueDto(requestId,
                                                clientId,
                                                now(),
                                                transactionProcessConfigurationProperties.sms(),
                                                smsPresaInCaricoInfo.getStatusAfterStart(),
                                                "sent",
                                                // TODO: SET eventDetails
                                                "",
                                                generatedMessageDto));
                            });
                }).onErrorResume(snsSendException -> {
                    log.error("Errore durante l'invio dell'SMS: {}", snsSendException.getMessage());
                    return retrySmsSend(acknowledgment,
                            smsPresaInCaricoInfo,
                            smsPresaInCaricoInfo.getStatusAfterStart(),
                            null)
                            .onErrorResume(throwable -> {
                                return null;
                            });

                })
                .doOnSuccess(result -> {
                    acknowledgment.acknowledge();
                    log.info("Il messaggio è stato gestito correttamente e rimosso dalla coda: {}", smsSqsQueueName.errorName());
                })
                .subscribe();

    }
}
