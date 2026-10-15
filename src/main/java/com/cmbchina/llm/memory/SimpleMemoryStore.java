package com.cmbchina.llm.memory;



import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;



@Component
public class SimpleMemoryStore
        implements MemoryStore {



    private final List<Memory> memories =
            new ArrayList<>();




    @Override
    public void save(
            Memory memory
    ){

        memories.add(
                memory
        );

    }




    @Override
    public List<Memory> query(
            String keyword
    ){


        List<Memory> result =
                new ArrayList<>();



        for(Memory memory:memories){


            if(memory.getContent()
                    .contains(keyword)){


                result.add(memory);

            }

        }


        return result;


    }




    @Override
    public void clear(){

        memories.clear();

    }



}