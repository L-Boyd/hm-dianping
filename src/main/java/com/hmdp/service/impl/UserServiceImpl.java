package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 验证手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 发送验证码
        log.debug("发送验证码成功:{}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
        if (!phone.equals(session.getAttribute("phone"))) {
            return Result.fail("发送验证码的手机号与登录手机号不一致");
        }

        // 校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        //根据手机号查用户
        //User user = this.lambdaQuery().eq(User::getPhone, phone).one();
        User user = query().eq("phone", phone).one();
        // 没有则注册
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 保存用户信息到redis
        // 随机生成token，作为登陆令牌
        String token = RedisConstants.LOGIN_USER_KEY + UUID.randomUUID().toString(true);
        // 将user对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        stringRedisTemplate.opsForHash().putAll(token, userMap);
        // 设置token有效期
        stringRedisTemplate.expire(token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 返回token
        return Result.ok(token);
    }

    // 用手机号注册账号
    private User createUserWithPhone(String phone) {
        User newUser = new User();
        newUser.setPhone(phone);
        newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        this.save(newUser);
        return newUser;
    }
}
