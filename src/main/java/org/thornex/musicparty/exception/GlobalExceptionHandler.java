package org.thornex.musicparty.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Map;

@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * 直接寫入響應體，不經內容協商。
     *
     * 原因：/radio/stream 以 {@code produces = "audio/mpeg"} 預設了 Content-Type，
     * 若此時由 @ExceptionHandler 回傳 Map/ResponseEntity，Jackson 在該 Content-Type 下
     * 找不到轉換器，會拋 HttpMessageNotWritableException——
     * 即日誌中的 "No converter for [...] with preset Content-Type 'audio/mpeg'"，
     * 客戶端因此拿到壞掉的 500 而非 JSON。此處先 reset 再以 JSON 落盤，杜絕該二次失敗。
     */
    private void writeJson(HttpServletResponse response, HttpStatus status, Map<String, Object> body) {
        if (response.isCommitted()) {
            return;
        }
        try {
            response.reset();
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), body);
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to write error response: {}", e.getMessage());
        }
    }

    /**
     * 可預期條件（非程式錯誤）：
     *  - 異步請求超時：上游（網易雲 / yt-dlp）慢於 spring.mvc.async.request-timeout；
     *  - 無此資源：多為客戶端把 URL 當路徑請求（例如把鏈接貼進搜索框）。
     * 兩者降為 WARN/DEBUG 且不打印堆棧，避免噪聲掩蓋真錯誤。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletResponse response) {
        log.warn("Async request timed out before the upstream responded");
        writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, Map.of(
                "message", "請求超時：上游響應過慢",
                "status", HttpStatus.SERVICE_UNAVAILABLE.value()
        ));
    }

    /**
     * 客户端主动断开（拖动进度条、关闭页面、切歌时中止 Range 请求）会抛 Broken pipe。
     * 这是正常现象，不是服务端错误；也不应再往已断开的响应里写 body。
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(org.apache.catalina.connector.ClientAbortException ex) {
        log.debug("Client aborted the response: {}", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNoResource(NoResourceFoundException ex, HttpServletResponse response) {
        log.debug("No resource for path: {}", ex.getResourcePath());
        writeJson(response, HttpStatus.NOT_FOUND, Map.of(
                "message", "Not found",
                "status", HttpStatus.NOT_FOUND.value()
        ));
    }

    @ExceptionHandler(ApiRequestException.class)
    public void handleApiRequestException(ApiRequestException ex, HttpServletResponse response) {
        // 外部 API（網易雲/yt-dlp 等）失敗一律視為上游錯誤
        writeJson(response, HttpStatus.BAD_GATEWAY, Map.of(
                "message", String.valueOf(ex.getMessage()),
                "status", HttpStatus.BAD_GATEWAY.value()
        ));
    }

    @ExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, HttpServletResponse response) {
        log.error("Unhandled exception", ex);
        writeJson(response, HttpStatus.INTERNAL_SERVER_ERROR, Map.of(
                "message", "An unexpected internal server error occurred.",
                "error", ex.getClass().getSimpleName(),
                "status", HttpStatus.INTERNAL_SERVER_ERROR.value()
        ));
    }
}
