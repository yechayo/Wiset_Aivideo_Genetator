package com.comic.security;

import com.comic.entity.User;
import com.comic.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

/**
 * Spring Security 用户加载实现
 * 登录时 Spring Security 会调用 loadUserByUsername 来验证用户名密码
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())   // BCrypt 密文，Spring Security 会自动对比
                .authorities("ROLE_USER")
                .build();
    }
}
