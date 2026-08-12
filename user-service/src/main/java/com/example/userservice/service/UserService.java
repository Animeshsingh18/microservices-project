package com.example.userservice.service;

import com.example.userservice.dto.*;
import com.example.userservice.entity.OutboxEvent;
import com.example.userservice.entity.User;
import com.example.userservice.event.UserEventFactory;
import com.example.userservice.exception.EmailAlreadyExistsException;
import com.example.userservice.exception.InvalidCredentialsException;
import com.example.userservice.exception.UserNotFoundException;
import com.example.userservice.repository.OutboxEventRepository;
import com.example.userservice.repository.UserRepository;
import com.example.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserEventFactory eventFactory;


    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();
        user = userRepository.save(user);

        String payload = eventFactory.build("USER_REGISTERED", Map.of(
                "userId", user.getId(),
                "name", user.getName(),
                "email", user.getEmail()
        ));

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("USER")
                .aggregateId(String.valueOf(user.getId()))
                .eventType("USER_REGISTERED")
                .payload(payload)
                .build();
        outboxEventRepository.save(outboxEvent);

        log.info("Registered user {} and queued USER_REGISTERED outbox event {}", user.getId(), outboxEvent.getId());

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return AuthResponse.of(token, toResponse(user));
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return AuthResponse.of(token, toResponse(user));
    }

    public UserResponse getById(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
    }
}
