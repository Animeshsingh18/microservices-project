package com.example.notificationservice.service;

public interface NotificationSender {

    void send(String recipient, String subject, String body) throws Exception;
}
