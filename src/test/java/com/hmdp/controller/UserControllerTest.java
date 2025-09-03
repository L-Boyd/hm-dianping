package com.hmdp.controller;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.session.StandardSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Slf4j
class UserControllerTest {

    @Resource
    private UserController userController;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void login() {
        List<String> phoneList = userService.lambdaQuery().list().stream().map(User::getPhone).collect(Collectors.toList());

        for (String phone : phoneList) {
            userController.sendCode(phone, new StandardSession(null));
            String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);

            LoginFormDTO loginFormDTO = new LoginFormDTO();
            loginFormDTO.setPhone(phone);
            loginFormDTO.setCode(code);
            userController.login(loginFormDTO, new StandardSession(null));
        }
    }

    @Test
    void writeTokensTxt() {
        // 获取所有token
        Set<String> tokens = stringRedisTemplate.keys("login:token:*");

        // 写入文件
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("tokens.txt"))) {
            for (String token : tokens) {
                writer.write(token);
                writer.newLine();
            }
            log.info("成功写入 {} 个token到tokens.txt", tokens.size());
        } catch (IOException e) {
            log.error("写入tokens.txt文件失败", e);
        }
    }
}