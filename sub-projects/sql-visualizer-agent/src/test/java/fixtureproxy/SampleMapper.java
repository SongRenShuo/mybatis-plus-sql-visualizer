package fixtureproxy;

/**
 * T-HdAbsorb D217 测试夹具：刻意放在 agent 自身包（{@code cn.song.mybatisplus.sqlvis.}）
 * <b>之外</b>的「业务」接口——代理反解的框架门（{@code StackLocator.isFrameworkFrame}）
 * 会把自身包当框架跳过，夹具接口若嵌在测试类里，反解恒 null、断言假绿会变成假红。
 * 也可用于演示「代理帧→业务接口」的端到端形态。
 */
public interface SampleMapper {
    void selectById();
}
