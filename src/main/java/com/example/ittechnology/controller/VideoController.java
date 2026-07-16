package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;

/**
 * 视频生成代理 — 对接 doubao-seedance-2-0-260128（火山豆包格式）
 *
 * 提交任务：POST /api/video/submit
 *   上游：POST https://ai-api.kkidc.com/api/v3/contents/generations/tasks
 *   返回：{ task_id: "cgt-xxx" }
 *
 * 轮询状态：GET /api/video/poll/{taskId}
 *   上游：GET https://ai-api.kkidc.com/api/v3/contents/generations/tasks/{task_id}
 *   状态：NOT_START / SUBMITTED / QUEUED / IN_PROGRESS / SUCCESS / FAILURE
 *   完成后在 data.data.content.video_url 取视频地址
 *
 * 请求体示例（submit）：
 * {
 *   "model": "doubao-seedance-2-0-260128",
 *   "prompt": "...",
 *   "generate_audio": true,
 *   "ratio": "16:9",
 *   "duration": 5,
 *   "resolution": "720p",
 *   "watermark": false
 * }
 */
@Slf4j
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {

    private final AiProxyService proxyService;

    /**
     * 提交视频生成任务，返回 task_id。
     * 前端拿到 task_id 后每隔 3s 调用 /poll/{taskId} 轮询进度。
     */
    @PostMapping(value = "/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> submitTask(
            @RequestHeader("Authorization") String auth,
            @RequestBody String body) {

        log.info("Video task submit received");

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(
                        "/api/v3/contents/generations/tasks", auth, body, Duration.ofSeconds(60), out);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(stream);
    }

    /**
     * 轮询视频任务状态。
     * 前端每 3s 调用一次，直到 status = SUCCESS 或 FAILURE。
     * 成功时取 data.data.content.video_url 作为视频播放/下载地址。
     */
    @GetMapping(value = "/poll/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pollTask(
            @RequestHeader("Authorization") String auth,
            @PathVariable String taskId) {

        log.debug("Polling video task: {}", taskId);
        String result = proxyService.proxyGet(
                "/api/v3/contents/generations/tasks/" + taskId, auth, Duration.ofSeconds(30));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }
}
