package cn.ianlee.example.biz.bank.common;

import org.springframework.jdbc.core.ColumnMapRowMapper;

import java.sql.Timestamp;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author Ian.Lee
 * @Description 查询结果映射工具。
 * H2/Oracle 等数据库返回的列名为大写，此处使用忽略大小写的 Map，
 * 避免 demo 代码里出现 ORDER_NO / order_no 混用问题
 */
public final class RowMapUtil {

    /** 列名忽略大小写的行映射器 */
    public static final ColumnMapRowMapper INSENSITIVE_MAPPER = new ColumnMapRowMapper() {
        @Override
        protected Map<String, Object> createColumnMap(int columnCount) {
            return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }
    };

    private RowMapUtil() {
    }

    public static String getString(Map<String, Object> row, String column) {
        Object value = row == null ? null : row.get(column);
        return value == null ? null : String.valueOf(value);
    }

    public static long getLong(Map<String, Object> row, String column) {
        Number value = (Number) (row == null ? null : row.get(column));
        return value == null ? 0L : value.longValue();
    }

    public static int getInt(Map<String, Object> row, String column) {
        Number value = (Number) (row == null ? null : row.get(column));
        return value == null ? 0 : value.intValue();
    }

    public static Timestamp getTimestamp(Map<String, Object> row, String column) {
        Object value = row == null ? null : row.get(column);
        return value instanceof Timestamp ? (Timestamp) value : null;
    }
}
