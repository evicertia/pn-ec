package it.pagopa.pn.library.pec.service.impl;

import it.pagopa.pn.commons.utils.MDCUtils;
import it.pagopa.pn.library.pec.configurationproperties.ArubaServiceProperties;
import it.pagopa.pn.library.pec.exception.aruba.ArubaCallException;
import it.pagopa.pn.library.pec.exception.aruba.ArubaCallMaxRetriesExceededException;
import it.pagopa.pn.library.pec.model.pojo.ArubaSecretValue;
import it.pagopa.pn.library.pec.service.ArubaService;
import it.pec.bridgews.*;
import lombok.CustomLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.retry.Retry;

import java.time.Duration;

import static it.pagopa.pn.ec.commons.utils.LogUtils.*;


@Component
@CustomLog
public class ArubaServiceImpl implements ArubaService {


    private final PecImapBridge pecImapBridgeClient;

    private final ArubaSecretValue arubaSecretValue;

    private final ArubaServiceProperties arubaServiceProperties;

    private static final int MESSAGE_NOT_FOUND_ERR_CODE = 99;

    @Autowired
    public ArubaServiceImpl(PecImapBridge pecImapBridgeClient, ArubaSecretValue arubaSecretValue, ArubaServiceProperties arubaServiceProperties) {
        this.pecImapBridgeClient = pecImapBridgeClient;
        this.arubaSecretValue = arubaSecretValue;
        this.arubaServiceProperties = arubaServiceProperties;
    }

    private Retry getArubaCallRetryStrategy(String clientMethodName) {
        var mdcContextMap = MDCUtils.retrieveMDCContextMap();
        return Retry.backoff(Long.parseLong(arubaServiceProperties.maxAttempts()), Duration.ofSeconds(Long.parseLong(arubaServiceProperties.minBackoff())))
                .doBeforeRetry(retrySignal -> {
                    MDCUtils.enrichWithMDC(null, mdcContextMap);
                    log.debug("Retry number {} for '{}', caused by : {}", retrySignal.totalRetries(), clientMethodName, retrySignal.failure().getMessage(), retrySignal.failure());
                })
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                    throw new ArubaCallMaxRetriesExceededException();
                });
    }

    @Override
    public Mono<GetMessageCountResponse> getMessageCount(GetMessageCount getMessageCount) {
        getMessageCount.setUser(arubaSecretValue.getPecUsername());
        getMessageCount.setPass(arubaSecretValue.getPecPassword());
        log.debug(CLIENT_METHOD_INVOCATION_WITH_ARGS, ARUBA_GET_MESSAGE_COUNT, getMessageCount);
        return Mono.create(sink -> pecImapBridgeClient.getMessageCountAsync(getMessageCount, res -> {
                    try {
                        var result = res.get();
                        checkErrors(result.getErrcode(), result.getErrstr());
                        sink.success(result);
                    } catch (Exception e) {
                        endSoapRequest(sink, e);
                    }
                })).cast(GetMessageCountResponse.class).retryWhen(getArubaCallRetryStrategy(ARUBA_GET_MESSAGE_COUNT))
                .doOnSuccess(result -> log.info(CLIENT_METHOD_RETURN, ARUBA_GET_MESSAGE_COUNT, result));
    }

    @Override
    public Mono<DeleteMailResponse> deleteMail(DeleteMail deleteMail) {
        deleteMail.setUser(arubaSecretValue.getPecUsername());
        deleteMail.setPass(arubaSecretValue.getPecPassword());
        var mdcContextMap = MDCUtils.retrieveMDCContextMap();
        log.debug(CLIENT_METHOD_INVOCATION_WITH_ARGS, ARUBA_DELETE_MAIL, deleteMail);
        return Mono.create(sink -> pecImapBridgeClient.deleteMailAsync(deleteMail, res -> {
                    try {
                        var result = res.get();
                        checkErrors(result.getErrcode(), result.getErrstr());
                        sink.success(result);
                    } catch (ArubaCallException arubaCallException) {
                        MDCUtils.enrichWithMDC(null, mdcContextMap);
                        if (arubaCallException.getErrorCode() == MESSAGE_NOT_FOUND_ERR_CODE) {
                            log.debug(ARUBA_MESSAGE_MISSING, deleteMail.getMailid());
                            sink.success();
                        } else endSoapRequest(sink, arubaCallException);
                    } catch (Exception e) {
                        MDCUtils.enrichWithMDC(null, mdcContextMap);
                        endSoapRequest(sink, e);
                    }
                })).cast(DeleteMailResponse.class).retryWhen(getArubaCallRetryStrategy(ARUBA_DELETE_MAIL))
                .doOnSuccess(result -> log.info(CLIENT_METHOD_RETURN, ARUBA_DELETE_MAIL, result));
    }

    private void checkErrors(Integer errorCode, String errorStr) {
        if (!errorCode.equals(0))
            throw new ArubaCallException(errorStr, errorCode);
    }

    private void endSoapRequest(MonoSink<Object> sink, Throwable throwable) {
        log.error(throwable.getMessage());
        sink.error(throwable);
        Thread.currentThread().interrupt();
    }

}
