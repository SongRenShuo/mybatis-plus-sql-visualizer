package cn.song.mybatisplus.visualizer.xml.ibatis;

/**
 * 渲染期硬错误（引擎同样抛出的形态，如 iterate 的属性不是集合/数组/迭代器）。
 */
public class IbatisRenderException extends RuntimeException {

    public IbatisRenderException(String message) {
        super(message);
    }
}
