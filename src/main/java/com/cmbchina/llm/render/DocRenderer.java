package com.cmbchina.llm.render;


import com.cmbchina.llm.entity.model.DocContentDTO;

import java.io.File;
import java.nio.file.Path;

public interface DocRenderer {

    /**
     * 根据 DocContentDTO 渲染文档
     *
     * @param dto        文档数据
     * @param outputDir  输出目录
     * @return 生成的文件
     * @throws Exception
     */
    File render(DocContentDTO dto, Path outputDir) throws Exception;
}
