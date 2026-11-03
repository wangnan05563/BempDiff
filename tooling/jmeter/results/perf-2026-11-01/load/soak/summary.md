# soak 汇总（2026-11-02 02:04:02）

| 计划 | 线程 | 墙钟(s) | 请求 | TPS | 错误率 | avg | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|---|---|---|
| soak:server_read | 4 | 6589 | 13080718 | 1985.15 | 0.0% | None | None | None | 70 | None |
| soak:harness | 4 | 3914 | 429268 | 109.68 | 0.0% | None | None | None | 1609 | None |
| soak:write | 4 | 2395 | 1929 | 0.81 | 0.0% | None | None | None | 1646 | None |

## 资源峰值

| SUT | 样本 | RSS max | Private max | CPU avg | CPU max | 线程 max | 句柄 max |
|---|---|---|---|---|---|---|---|
| server | 7063 | 445MB | 650MB | 61.2% | 298.3% | 0 | 417 |
| harness | 7062 | 403MB | 646MB | 35.7% | 328.2% | 0 | 536 |
| _note_ jvm_probe | {'server': {'samples': 947, 'span_hours': 3.98, 'threads_first': 26, 'threads_last': 30, 'threads_max': 41, 'handles_first': 334, 'handles_last': 369, 'handles_max': 416, 'rss_first_mb': 58.3, 'rss_last_mb': 444.6, 'rss_max_mb': 445.0, 'rss_growth_mb_per_hour': 97.0, 'threads_growth_per_hour': 1.0}, 'harness': {'samples': 947, 'span_hours': 3.98, 'threads_first': 26, 'threads_last': 60, 'threads_max': 62, 'handles_first': 324, 'handles_last': 533, 'handles_max': 538, 'rss_first_mb': 52.6, 'rss_last_mb': 402.0, 'rss_max_mb': 402.0, 'rss_growth_mb_per_hour': 87.7, 'threads_growth_per_hour': 8.54}} | | | | | | |

## 端点明细
