package com.cmbchina.llm.util;


import java.io.ByteArrayOutputStream;
import java.io.InputStream;



public class StreamUtil {



    public static String read(
            InputStream input
    ) throws Exception{


        ByteArrayOutputStream out =
                new ByteArrayOutputStream();



        byte[] buffer =
                new byte[1024];



        int len;


        while(
                (len=input.read(buffer))
                        !=-1
        ){

            out.write(
                    buffer,
                    0,
                    len
            );

        }



        return new String(
                out.toByteArray(),
                "UTF-8"
        );

    }


}