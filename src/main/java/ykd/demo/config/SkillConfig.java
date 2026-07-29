package ykd.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ykd.demo.skill.SkillManager;

@Configuration
public class SkillConfig {

    @Bean
    public SkillManager skillManager() {
        SkillManager sm = new SkillManager();
        sm.load();
        return sm;
    }
}
