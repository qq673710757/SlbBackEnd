package com.slb.mining_backend.modules.users.service;

import com.slb.mining_backend.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    // 从配置文件中获取发件人邮箱地址
    @Value("${spring.mail.username}")
    private String fromEmail;

    /**
     * 发送简单的文本邮件
     * @param to 收件人邮箱
     * @param subject 邮件主题
     * @param text 邮件内容
     */
    public void sendEmail(String to, String subject, String text) {
        // 创建一个简单的邮件消息对象
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail); // 设置发件人
        message.setTo(to);          // 设置收件人
        message.setSubject(subject);  // 设置主题
        message.setText(text);      // 设置内容

        try {
            // 发送邮件
            mailSender.send(message);
            log.info("邮件已成功发送至: {}", to);
        } catch (MailException e) {
            // 如果发送失败，记录错误日志并抛出业务异常
            log.error("发送邮件至 {} 时发生错误: {}", to, e.getMessage());
            throw new BizException("邮件发送失败，请稍后重试");
        }
    }
}
