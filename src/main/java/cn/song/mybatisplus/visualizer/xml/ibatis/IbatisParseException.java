package cn.song.mybatisplus.visualizer.xml.ibatis;

/**
 * iBatis 文件结构级失败（XML 语法错 / 根元素不是 {@code <sqlMap>}）——语句级问题一律降级为 warning，
 * 只有这类结构失败才抛本异常。
 */
public class IbatisParseException extends RuntimeException {

    public IbatisParseException(String message) {
        super(message);
    }
}
