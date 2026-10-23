package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频生成代理 — 对接 doubao-seedance-2-0-260128（火山豆包格式）
 *
 * 提交任务：POST /api/video/submit（X-Site 头选站，默认国内站）
 *   国内站上游：POST /api/v3/contents/generations/tasks（扁平参数格式）
 *   国际站上游：POST /v1/video/generations（参数包在 metadata 中，前端按站组装）
 *   返回：{ task_id: "cgt-xxx" }
 *
 * 轮询状态：GET /api/video/poll/{taskId}
 *   国内站上游：GET /api/v3/contents/generations/tasks/{task_id}
 *   国际站上游：GET /v1/video/generations/{task_id}
 *   状态：NOT_START / SUBMITTED / QUEUED / IN_PROGRESS / SUCCESS / FAILURE
 *   完成后在 data.data.content.video_url 取视频地址
 *
 * 请求体示例（submit，国内站扁平参数）：
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
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body,
            HttpServletResponse response) {

        log.info("Video task submit received [site={}]", site);

        // 国内站与国际站的创建路径不同：
        //   cn:   POST /api/v3/contents/generations/tasks（扁平参数格式）
        //   intl: POST /v1/video/generations（参数包在 metadata 中）
        String path = "intl".equalsIgnoreCase(site)
                ? "/v1/video/generations"
                : "/api/v3/contents/generations/tasks";

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(
                        site, path, auth, body, Duration.ofSeconds(60), out, response);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(stream);
    }

    /**
     * 提交视频生成任务（国际站多模态-参考生视频），返回 task_id。
     *
     * 上游：POST /v1/video/generations（火山豆包 seed-2.0 多模态格式）
     * 与 /submit 的区别：参考素材（图/视频/音频 URL）放在 metadata.content 数组中，
     * 不支持 resolution 参数。
     *
     * 请求体示例（submit-intl）：
     * {
     *   "model": "doubao-seedance-2-0-fast-260128",
     *   "prompt": "...",
     *   "metadata": {
     *     "content": [
     *       {"type":"image_url","image_url":{"url":"https://..."},"role":"reference_image"},
     *       {"type":"video_url","video_url":{"url":"https://..."},"role":"reference_video"},
     *       {"type":"audio_url","audio_url":{"url":"https://..."},"role":"reference_audio"}
     *     ],
     *     "generate_audio": true,
     *     "ratio": "16:9",
     *     "duration": 10,
     *     "watermark": false
     *   }
     * }
     *
     * 注意：涉及人脸/虚拟人图片需先走素材库接口过审，否则生成会失败。
     * 返回的 task_id（cgt-xxx）与 /submit 同属一套任务系统，可直接用 /poll/{taskId} 轮询。
     */
    @PostMapping(value = "/submit-intl", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> submitIntlTask(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "intl") String site,
            @RequestBody String body,
            HttpServletResponse response) {

        log.info("Intl video task submit received [site={}]", site);

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(
                        site, "/v1/video/generations", auth, body, Duration.ofSeconds(60), out, response);

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
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @PathVariable String taskId,
            HttpServletResponse response) {

        log.debug("Polling video task: {} [site={}]", taskId, site);
        // 国内站与国际站的轮询路径不同：
        //   cn:   GET /api/v3/contents/generations/tasks/{task_id}
        //   intl: GET /v1/video/generations/{task_id}
        String base = "intl".equalsIgnoreCase(site)
                ? "/v1/video/generations/"
                : "/api/v3/contents/generations/tasks/";
        AtomicInteger status = new AtomicInteger(200);
        String result = proxyService.proxyGet(
                site, base + taskId, auth, Duration.ofSeconds(30), status);
        response.setHeader("X-Upstream-Status", String.valueOf(status.get()));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }

    /**
     * 轮询视频任务状态（JSON 直发专用）：
     * 固定走 GET /v1/video/generations/{task_id}（与 /api/test/video-generate 的上游路径一致，
     * 不按站点切换轮询路径），站点的 base 地址仍由 X-Site 决定。
     */
    @GetMapping(value = "/poll-v1/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pollTaskV1(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @PathVariable String taskId,
            HttpServletResponse response) {

        log.debug("Polling v1 video task: {} [site={}]", taskId, site);
        AtomicInteger status = new AtomicInteger(200);
        String result = proxyService.proxyGet(
                site, "/v1/video/generations/" + taskId, auth, Duration.ofSeconds(30), status);
        response.setHeader("X-Upstream-Status", String.valueOf(status.get()));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }

    // ==================== MiniMax V2 接口 ====================

    /**
     * 提交 MiniMax 视频生成任务（V2 接口）。
     * 支持文生视频 / 图生视频（首帧 / 尾帧 / 首尾帧）。
     * 上游：POST /v2/video_generation
     * 返回：{ task_id: "task_xxx" }
     */
    @PostMapping(value = "/submit-minimax", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> submitMinimax(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body,
            HttpServletResponse response) {

        log.info("MiniMax video task submit received [site={}]", site);

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(
                        site, "/v2/video_generation", auth, body, Duration.ofSeconds(60), out, response);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(stream);
    }

    /**
     * 轮询 MiniMax 视频任务状态。
     * 上游：GET /v2/video_generation/{task_id}
     */
    @GetMapping(value = "/poll-minimax/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pollMinimax(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @PathVariable String taskId,
            HttpServletResponse response) {

        log.debug("Polling MiniMax video task: {} [site={}]", taskId, site);
        AtomicInteger status = new AtomicInteger(200);
        String result = proxyService.proxyGet(
                site, "/v2/video_generation/" + taskId, auth, Duration.ofSeconds(30), status);
        response.setHeader("X-Upstream-Status", String.valueOf(status.get()));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }
}
