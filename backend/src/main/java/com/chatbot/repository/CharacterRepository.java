package com.chatbot.repository;

import com.chatbot.entity.CharacterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CharacterRepository extends JpaRepository<CharacterEntity, String> {

    List<CharacterEntity> findByEnabledTrueOrderBySortOrderAsc();
}
