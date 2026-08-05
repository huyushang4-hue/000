package ykd.demo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 启动时自动修复数据库兼容问题：
 * 删除表里代码已不使用的遗留列（ddl-auto=update 不会删列，旧分支建表遗留的列会导致插入失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbCompatibilityFixer {

    private final JdbcTemplate jdbc;

    /** scheduled_task 表当前实体使用的列（其余为历史遗留列，启动时自动删除） */
    private static final Set<String> SCHEDULED_TASK_KEEP = Set.of(
            "id", "type", "cron_expression", "execute_at", "status",
            "task_name", "task_params", "user_id", "result", "created_at", "updated_at");

    @jakarta.annotation.PostConstruct
    public void fixLegacyColumns() {
        dropLegacyColumns("scheduled_task", SCHEDULED_TASK_KEEP);
    }

    /** 删除表中不在保留列表内的所有列（幂等，逐列删除避免一条失败全失败） */
    private void dropLegacyColumns(String table, Set<String> keep) {
        try {
            List<String> cols = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = ?",
                    String.class, table);
            for (String col : cols) {
                if (keep.contains(col.toLowerCase())) continue;
                try {
                    jdbc.execute("ALTER TABLE " + table + " DROP COLUMN `" + col + "`");
                    log.info("已删除 {}.{} 遗留列", table, col);
                } catch (Exception e) {
                    log.warn("删除 {}.{} 失败: {}", table, col, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("数据库兼容修复跳过 {}: {}", table, e.getMessage());
        }
    }
}
