“三方融合搜索算法”，专门为结构化字段匹配进行优化：

Alias（别名精确）+ 加权语义向量匹配（Embedding）+ Fuzzy/Correction（兜底）三网合一

整个设计同时确保：

    精确优先（alias、拼写纠正）
    
    语义为主（desc 和 name 加权）
    
    fuzzy 不“抢答”（仅当前两者失败才进入）
    
    可控 topK，多候选融合打分

✔ alias 精确秒杀

✔ correction 精确秒杀

✔ vector 占主导

✔ fuzzy 兜底不抢答

✔ topK 的所有候选都在 candidates 里

✔ best 是融合后的最终答案

✔ 每个候选都带

    评分
    
    来源（vector / alias / fuzzy）