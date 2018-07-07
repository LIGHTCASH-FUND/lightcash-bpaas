package com.lc.bpaas.pay;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class CallbackServiceImpl {
	private static final Logger logger = LoggerFactory.getLogger(CallbackServiceImpl.class);
	@Autowired
	private OkHttpService okHttpService;
	@Autowired
	private MQProducer producer;
	private static final Map<Integer,Integer> delayMap = new HashMap<>();
	@Autowired
	private SignInterfaceRecordService signInterfaceRecordService;

	static {
		delayMap.put(1,3*1000);
		delayMap.put(2,30*1000);
		delayMap.put(3,30*1000*6);
		delayMap.put(4,30*1000*60);
		delayMap.put(5,1000*60*60);
	}
	public final static String HASH_KEY_CALLBACKTIMES  = "callbackTimes";
	public final static String HASH_KEY_EXPIRETIME  = "expireTime";
	public final static long EXPIRETIME  = 60000*60*24;
	

	@Override
	public String callback(String notifyUrl, String userId, String orderId, String topic,Object messageBody,String fullMessage) {
		if(StringUtils.isEmpty(notifyUrl)||!notifyUrl.startsWith("h")){
			logger.info("notifyurl empty");
		    return "FAIL";
		}
		String key = RedisKeyConstants.NOTICE_PAY_CALLBACK_PRE + userId+"@"+orderId;
		Integer callbackTimes = null;
		Integer delayLevel = null;
		Long expTime=  null;
		if(cts == null){
			expTime = System.currentTimeMillis() + EXPIRETIME;
			delayLevel = new Integer(1);
			callbackTimes  = new Integer(1);
		} else {
			callbackTimes = Integer.valueOf(cts);
		}
		delayLevel = delayMap.get(callbackTimes);
		if(ets != null){
			expTime = Long.valueOf(ets);
			long currentTimeMillis = System.currentTimeMillis();
			if(expTime != null && expTime < currentTimeMillis){
				logger.info("past due：" + JSON.toJSONString(messageBody));
				return "FAIL";
			}
		}
		if(callbackTimes!=null&&callbackTimes >= 5){
			delayLevel = delayMap.get(5);
		}
		String result = null;
		try {
			result = okHttpService.doPost(notifyUrl, JSON.toJSONString(messageBody));
			if(StringUtils.isEmpty(result) || !result.contains(BusinessEnum.ResultCode.SUCCESS.getCode())){
				logger.error("return："+result);
				producer.sendDelay(topic,null, fullMessage,key,delayLevel.longValue());
			} else {
				SignInterfaceRecord record = new SignInterfaceRecord();
				String appId = "";
				if(messageBody instanceof AsyncPayResult){
				    appId  = ((AsyncPayResult) messageBody).getAppId();
				}
				record.setAppId(appId);
				record.setInterfaceName(notifyUrl);
				record.setRequestDatetime(new Date());
				record.setRequestContent(fullMessage);
				record.setRequestType((byte)1);
				Boolean flag = signInterfaceRecordService.insertRecord(record);
			}
		} catch (Exception e) {
			logger.error("callback:"+fullMessage,e);
			producer.sendDelay(topic,null,fullMessage,userId+"-"+orderId,delayLevel.longValue());
		}
		return result;
	}

}
