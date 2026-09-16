package com.chatbot.service;

import com.chatbot.dto.Dtos;
import com.chatbot.entity.CharacterEntity;
import com.chatbot.repository.CharacterRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class CharacterService {

    private final CharacterRepository characterRepository;

    public CharacterService(CharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    public List<Dtos.CharacterDto> listCharacters() {
        return characterRepository.findByEnabledTrueOrderBySortOrderAsc().stream()
                .map(this::toDto)
                .toList();
    }

    public Dtos.CharacterDto getCharacter(String id) {
        return toDto(findEnabled(id));
    }

    public CharacterEntity findEnabled(String id) {
        return characterRepository.findById(id)
                .filter(CharacterEntity::getEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "人物不存在: " + id));
    }

    private Dtos.CharacterDto toDto(CharacterEntity c) {
        return new Dtos.CharacterDto(
                c.getId(),
                c.getName(),
                c.getDynasty(),
                c.getTitle(),
                c.getTagline(),
                c.getAvatar(),
                c.getGreeting()
        );
    }
}
