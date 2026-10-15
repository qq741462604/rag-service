package com.cmbchina.llm.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import java.io.File;


@Component
public class AgentHomeService {


    private final String home;


    public AgentHomeService(
            @Value("${minicodex.home}")
            String home
    ){

        this.home=home;

    }



    public File resolve(
            String path
    ){

        return new File(
                home,
                path
        );

    }


}