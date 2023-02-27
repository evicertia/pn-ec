package it.pagopa.pn.ec.cartaceo.service;

import it.pagopa.pn.ec.cartaceo.configurationproperties.CartaceoSqsQueueName;
import it.pagopa.pn.ec.cartaceo.model.pojo.CartaceoPresaInCaricoInfo;
import it.pagopa.pn.ec.commons.configurationproperties.TransactionProcessConfigurationProperties;
import it.pagopa.pn.ec.commons.configurationproperties.sqs.NotificationTrackerSqsName;
import it.pagopa.pn.ec.commons.exception.EcInternalEndpointHttpException;
import it.pagopa.pn.ec.commons.model.dto.NotificationTrackerQueueDto;
import it.pagopa.pn.ec.commons.model.pojo.PresaInCaricoInfo;
import it.pagopa.pn.ec.commons.rest.call.ec.gestorerepository.GestoreRepositoryCall;
import it.pagopa.pn.ec.commons.service.AuthService;
import it.pagopa.pn.ec.commons.service.PresaInCaricoService;
import it.pagopa.pn.ec.commons.service.SqsService;
import it.pagopa.pn.ec.commons.service.impl.AttachmentServiceImpl;
import it.pagopa.pn.ec.rest.v1.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;


import java.util.Collections;
import static java.time.OffsetDateTime.now;

@Service
@Slf4j
public class CartaceoService  extends PresaInCaricoService {




    private final SqsService sqsService;
    private final GestoreRepositoryCall gestoreRepositoryCall;
    private final AttachmentServiceImpl attachmentService;
    private final NotificationTrackerSqsName notificationTrackerSqsName;
    private final CartaceoSqsQueueName cartaceoSqsQueueName;
    private final TransactionProcessConfigurationProperties transactionProcessConfigurationProperties;



    protected CartaceoService(AuthService authService, GestoreRepositoryCall gestoreRepositoryCall, SqsService sqsService, GestoreRepositoryCall gestoreRepositoryCall1, AttachmentServiceImpl attachmentService, NotificationTrackerSqsName notificationTrackerSqsName, CartaceoSqsQueueName cartaceoSqsQueueName, TransactionProcessConfigurationProperties transactionProcessConfigurationProperties) {
        super(authService, gestoreRepositoryCall);
        this.sqsService = sqsService;
        this.gestoreRepositoryCall = gestoreRepositoryCall1;
        this.attachmentService = attachmentService;
        this.notificationTrackerSqsName = notificationTrackerSqsName;
        this.cartaceoSqsQueueName = cartaceoSqsQueueName;
        this.transactionProcessConfigurationProperties = transactionProcessConfigurationProperties;
    }

    @Override
    protected Mono<Void> specificPresaInCarico(PresaInCaricoInfo presaInCaricoInfo) {
        CartaceoPresaInCaricoInfo cartaceoPresaInCaricoInfo = (CartaceoPresaInCaricoInfo)presaInCaricoInfo;
        return attachmentService.checkAllegatiPresence(Collections.singletonList(cartaceoPresaInCaricoInfo.getPaperEngageRequest().getAttachmentUrl()),
                        presaInCaricoInfo.getXPagopaExtchCxId(),
                        true)
                .flatMap(fileDownloadResponse -> {
                    var peperNotificationRequest = cartaceoPresaInCaricoInfo.getPaperEngageRequest();
                    peperNotificationRequest.setRequestId(presaInCaricoInfo.getRequestIdx());
                    return insertRequestFromCartaceo(peperNotificationRequest).onErrorResume(throwable -> Mono.error(new EcInternalEndpointHttpException()));
                })
                .flatMap(requestDto -> sqsService.send(notificationTrackerSqsName.statoCartaceoName(),
                        new NotificationTrackerQueueDto(presaInCaricoInfo.getRequestIdx(),
                                presaInCaricoInfo.getXPagopaExtchCxId(),
                                now(),
                                transactionProcessConfigurationProperties.paper(),
                                transactionProcessConfigurationProperties.paperStarterStatus(),
                                "booked",
                                null)))
                .flatMap(sendMessageResponse -> {
                    PaperEngageRequest req = cartaceoPresaInCaricoInfo.getPaperEngageRequest();
//                    if (qos =="" ) {
//                        return sqsService.send(cartaceoSqsQueueName.interactiveName(),
//                                cartaceoPresaInCaricoInfo.getPaperEngageRequest());
//                    } else if (qos == "") {
//                        return sqsService.send(cartaceoSqsQueueName.batchName(),
//                                cartaceoPresaInCaricoInfo.getPaperEngageRequest());
//                    } else {
//                        return Mono.empty();
//                    }
                            return sqsService.send(cartaceoSqsQueueName.interactiveName(),
                                cartaceoPresaInCaricoInfo.getPaperEngageRequest());
                        }
                )
                .then();
    }

    private Mono<RequestDto> insertRequestFromCartaceo(PaperEngageRequest peperNotificationRequest) {

        return Mono.fromCallable(() ->{
            var requestDto = new RequestDto();
            requestDto.setRequestIdx(peperNotificationRequest.getRequestId());
            requestDto.setClientRequestTimeStamp(peperNotificationRequest.getClientRequestTimeStamp());
            var requestPersonalDto = new RequestPersonalDto();
            var digitalRequestPersonalDto = new PaperRequestPersonalDto();

//            digitalRequestPersonalDto.setAttachments(attachments);
            digitalRequestPersonalDto.setReceiverNameRow2(peperNotificationRequest.getReceiverNameRow2());
            digitalRequestPersonalDto.setReceiverAddress(peperNotificationRequest.getReceiverAddress());
            digitalRequestPersonalDto.setReceiverAddressRow2(peperNotificationRequest.getReceiverAddressRow2());
            digitalRequestPersonalDto.setReceiverCap(peperNotificationRequest.getReceiverCap());
            digitalRequestPersonalDto.setReceiverCity(peperNotificationRequest.getReceiverCity());
            digitalRequestPersonalDto.setReceiverCity2(peperNotificationRequest.getReceiverCity2());
            digitalRequestPersonalDto.setReceiverPr(peperNotificationRequest.getReceiverPr());
            digitalRequestPersonalDto.setReceiverCountry(peperNotificationRequest.getReceiverCountry());
            digitalRequestPersonalDto.setReceiverFiscalCode(peperNotificationRequest.getReceiverFiscalCode());
            digitalRequestPersonalDto.setSenderName(peperNotificationRequest.getSenderName());
            digitalRequestPersonalDto.setSenderAddress(peperNotificationRequest.getSenderAddress());
            digitalRequestPersonalDto.setSenderCity(peperNotificationRequest.getSenderCity());
            digitalRequestPersonalDto.setSenderPr(peperNotificationRequest.getSenderPr());
            digitalRequestPersonalDto.setSenderDigitalAddress(peperNotificationRequest.getSenderDigitalAddress());
            digitalRequestPersonalDto.setArName(peperNotificationRequest.getArName());
            digitalRequestPersonalDto.setArAddress(peperNotificationRequest.getArAddress());
            digitalRequestPersonalDto.setArCap(peperNotificationRequest.getArCap());
            digitalRequestPersonalDto.setArCity(peperNotificationRequest.getArCity());






            var requestMetadataDto = new RequestMetadataDto();
            var digitalRequestMetadataDto = new PaperRequestMetadataDto();
            digitalRequestMetadataDto.setRequestPaId(peperNotificationRequest.getRequestPaId());
            digitalRequestMetadataDto.setIun(peperNotificationRequest.getIun());
            digitalRequestMetadataDto.setVas(peperNotificationRequest.getVas());
            digitalRequestMetadataDto.setPrintType(peperNotificationRequest.getPrintType());
            digitalRequestMetadataDto.setProductType(peperNotificationRequest.getProductType());


            requestDto.setRequestPersonal(requestPersonalDto);
            requestDto.setRequestMetadata(requestMetadataDto);
            return requestDto;
        }).flatMap(gestoreRepositoryCall::insertRichiesta);
    }

}
