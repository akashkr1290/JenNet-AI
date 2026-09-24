package com.jannetai.backend.security;

import com.jannetai.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * "Username" here is whatever identifier the client authenticated with
 * (mobile number or email, per SRS 15.2) - resolved once at login and
 * embedded as the JWT subject, so the filter path only ever looks up by
 * mobile/email indirectly via {@link #loadUserByUsername}.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String identifier) {
        return userRepository.findByMobileNumberOrEmail(identifier, identifier)
                .map(UserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user for identifier: " + identifier));
    }
}
