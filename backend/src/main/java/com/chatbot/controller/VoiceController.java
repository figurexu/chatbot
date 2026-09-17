package com.chatbot.controller;

import com.chatbot.service.VoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /** 语音识别（STT）：上传录音文件 → 返回识别文本 */
    @PostMapping("/stt")
    public Map<String, String> stt(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        String text = voiceService.recognize(file, request);
        return Map.of("text", text);
    }

    /** 语音合成（TTS）：文本 → mp3 音频 */
    @GetMapping("/tts")
    public ResponseEntity<byte[]> tts(@RequestParam("text") String text) {
        byte[] audio = voiceService.synthesize(text);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header("Content-Disposition", "inline")
                .body(audio);
    }

    /** 录音临时文件（供 ASR 公网拉取） */
    @GetMapping("/audio/{name}")
    public ResponseEntity<FileSystemResource> audio(@PathVariable String name) throws IOException {
        Path p = voiceService.resolveAudioFile(name);
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(voiceService.contentTypeOf(p)))
                .body(new FileSystemResource(p));
    }
}
