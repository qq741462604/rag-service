name:
ip解析增强

keywords:
IP
ip
ipCity

ipCountry

userIpVal




target:

class: DataPrepEventHandler

method: handle


implementation:

必须新增以下代码:

1. handle方法中:

原:

Map<String, Object> eventFields = eventData.getData();


后:

Map<String, Object> eventFields = eventData.getData();

getIpInfo(eventData);



  

  2. 必须新增方法:


  private void getIpInfo(EventData eventData) {


      try {
      
          String ipCity = "";
          String ipCountry = "";
          String ipPro = "";


          Map<String,Object> eventFields =
                  eventData.getData();


          HashMap<String,Object> resultMap =
                  new HashMap<>();


          Map<String,Object> result =
                  Collections.synchronizedMap(
                          resultMap
                  );


          result.put(IP_CITY, ipCity);
          result.put(IP_COUNTRY, ipCountry);
          result.put(IP_PRO, ipPro);


          String userIpVal =
                  eventFields
                  .getOrDefault(
                      "userIpVal",
                      ""
                  )
                  .toString();


          ipLocation(
              userIpVal,
              result
          );


          eventData.putDataAndOriginData(
              IP_CITY,
              result.get(IP_CITY)
          );


      }catch(Exception e){
      
          log.error(e.getMessage(),e);
      
      }

  }

  

  3. 必须新增:


  private static void ipLocation(
   String ip,
   Map<String,Object> result
  ){


      GeoInfo nc =
          GeoIpService.getInstance()
              .findCity(ip);


      if(nc!=null){
      
          result.put(
              IP_CITY,
              nc.getCityCode()
          );
      
          result.put(
              IP_COUNTRY,
              nc.getNation()
          );
      
          result.put(
              IP_PRO,
              nc.getProvCode()
          );
      }

  }

  禁止修改:

  - 方法名称
  - 参数
  - 调用方式
  - 字段名称
  - 兜底包名和伪造路径

  4. Target 已知且已存在时，必须先 search_code/read_file 读取其真实路径和 package，再做任何修改判断；禁止自行猜测 package 或生成 com.example、example 等兜底路径。

  5. 如果未来需要新增辅助类，必须以 Target 真实 package 为基准生成，不得偏离 Target 所在业务包。
