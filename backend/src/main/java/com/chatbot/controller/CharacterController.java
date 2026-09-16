package com.chatbot.controller;

import com.chatbot.dto.Dtos;
import com.chatbot.service.CharacterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {

    private final CharacterService characterService;

    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
    }

    /** 历史人物列表 */
    @GetMapping
    public List<Dtos.CharacterDto> list() {
        return characterService.listCharacters();
    }

    /** 人物详情（含开场白） */
    @GetMapping("/{id}")
    public Dtos.CharacterDto detail(@PathVariable String id) {
        return characterService.getCharacter(id);
    }
}
