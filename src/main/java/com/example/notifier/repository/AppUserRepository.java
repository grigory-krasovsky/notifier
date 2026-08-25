package com.example.notifier.repository;

import com.example.notifier.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByTelegramChatId(Long telegramChatId);
}
