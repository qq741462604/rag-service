package com.cmbchina.llm.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AgentMemoryService {


    private final MemoryStore memoryStore;



    public void saveTask(
            String task,
            String content
    ){


        memoryStore.save(

                Memory.builder()

                        .key(
                                "task_"+System.currentTimeMillis()
                        )
                        .content(
                                "TASK:\n"
                                        +task
                                        +"\nRESULT:\n"
                                        +content
                        )
                        .type(
                                MemoryType.TASK
                        )

                        .content(content)

                        .createTime(
                                new Date()
                        )

                        .build()

        );

    }



    public void saveVerifyError(
            String task,
            List<String> errors
    ){


        memoryStore.save(

                Memory.builder()

                        .key(
                                task+"_verify"
                        )

                        .type(
                                MemoryType.VERIFY_ERROR
                        )

                        .content(
                                String.join(
                                        "\n",
                                        errors
                                )
                        )

                        .createTime(
                                new Date()
                        )

                        .build()

        );


    }


}
