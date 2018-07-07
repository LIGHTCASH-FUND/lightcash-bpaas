package com.lc.bpaas.pay;

import static java.math.BigDecimal.ROUND_HALF_UP;

import java.math.BigDecimal;
import java.util.Date;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson.JSON;

@Service
public class OrderPayServiceImpl implements OrderPayService {
	private static final Logger logger = LoggerFactory.getLogger(OrderPayServiceImpl.class);
	
	@Autowired
	private MQProducer producer;

	@Autowired
	private CheckoutOrderMapper checkoutOrderMapper;

	@Autowired
	private UserRemoteService userService;
	@Autowired
	private PayRemoteService payService;
	@Autowired
	private WxPayService wxPayService;
	@Autowired
	private RedisTemplate redisTemplate;
	@Autowired
	private RefundRecordRemoteService refundRecordRemoteService;
	@Autowired
	private SystemParamsRemoteService systemParamsRemoteService;
	@Autowired
	private ThirdPayRecordService thirdPayRecordService;
	@Autowired
	private CheckoutOrderService checkoutOrderService;
	@Autowired
	private CheckoutConfigService checkoutConfigService;
	@Autowired
	private MerchantAppService merchantAppService;
	@Autowired
	private CoinInfoRemoteService coinInfoRemoteService;
	@Value("${api-rsa.privateKey}")
	private String privateKey;
	@Override
	public PayQRcodeInfo getPayQRcodeInfo(PayRequestParams params) {

		PayQRcodeInfo payQRcodeInfo = new PayQRcodeInfo();
		StringBuffer code = new StringBuffer();
		UserData userData = userService.getUserById(params.getUserId()).getData();
		if(userData == null){
		    throw new BusinessException(BusinessExceptionCode.USER_IS_NOT_EXIST);
		}
		payQRcodeInfo.setCode(userData.getUserId());
		payQRcodeInfo.setName(userData.getUserName());
		payQRcodeInfo.setUserId(userData.getUserId());

		return payQRcodeInfo;
	}
	
	@Override
	public OrderPayResult orderPay(PayParams params,String userId) {
		CheckoutConfig config = checkoutConfigService.selectConfigByUserId(params.getReceiptUserId(),params.getCoinType());
		TreeMap<String, String> signMap = new TreeMap<>();
		signMap.put("orderNumber",params.getOrderId());
		signMap.put("amount",params.getOriginalPrice().toString());
		signMap.put("appid",params.getReceiptUserId());
		signMap.put("notifyUrl",params.getNotifyUrl());
		if(params.getCashAmount() == null) {
			params.setCashAmount(BigDecimal.ZERO);
		}

		if(params.getAmount().compareTo(BigDecimal.ZERO) ==-1){
			throw new BusinessException(BusinessExceptionCode.ERROR_AMOUNT_OF_MONEY);
		}
		BigDecimal cashAmount = params.getCashAmount();
		if(cashAmount != null && cashAmount.compareTo(BigDecimal.ZERO) ==-1){
			throw new BusinessException(BusinessExceptionCode.ERROR_AMOUNT_OF_MONEY);
		}

		PayMoney payMoney = computeMoney(params.getReceiptUserId(),params.getOriginalPrice(),params.getCoinType());
		if(payMoney != null && cashAmount != null && cashAmount.compareTo(payMoney.getCashMoney()) == -1) {
			throw new BusinessException(BusinessExceptionCode.ERROR_AMOUNT_OF_MONEY);
		}

		BigDecimal otherCoinNum = mixedPaymentCoinToOtherCoin(params.getReceiptUserId(),params.getAmount(),params.getCoinType());
		if(params.getOriginalPrice().subtract(params.getCashAmount().add(otherCoinNum)).compareTo(new BigDecimal(0.01)) == 1) {
			throw new BusinessException(BusinessExceptionCode.ERROR_AMOUNT_OF_MONEY);
		}
		
		if(checkoutOrder != null) {
			Byte orderStatus = checkoutOrder.getOrderStatus();
			if(checkoutOrder.getThirdPayStatus() != null && checkoutOrder.getThirdPayStatus().equals(BusinessEnum.OrderStatus.PAID.getCode())&&
					orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())){
				throw new BusinessException(BusinessExceptionCode.CHECKOUT_ORDER_ID_DUPLICATE);
			}
			if(orderStatus.equals(BusinessEnum.OrderStatus.PAY_UNKOWN_ERROR.getCode())){
				throw new BusinessException(BusinessExceptionCode.PAY_PROCESSING);
			}
		}
		String orderId = UUIDUtils.getUuid();
		if(checkoutOrder == null) {
			BigDecimal cash = params.getCashAmount();
			BigDecimal originalPrice = params.getOriginalPrice();
			BigDecimal cashPercent = cash.divide(originalPrice,8,ROUND_HALF_UP);
			BigDecimal tokenPercent = BigDecimal.ONE.subtract(cashPercent).multiply(new BigDecimal(100));

			Byte coinScaleType = config.getPayCoinScaleType();
			checkoutOrder = new CheckoutOrder();
			checkoutOrder.setOrderStatus(BusinessEnum.OrderStatus.PAYING.getCode());
			checkoutOrder.setPayUserId(userId);
			checkoutOrder.setCoinNetworkType(params.getNetworkType());
			checkoutOrder.setOrderMoney(params.getAmount());
			checkoutOrder.setCoinType(params.getCoinType());
			checkoutOrder.setReceiptUserId(params.getReceiptUserId());
			checkoutOrder.setOrderId(orderId);
			checkoutOrder.setCreateDate(new Date());
			checkoutOrder.setUpdateDate(new Date());
			checkoutOrder.setOrderFlowCode(params.getOrderId());
			checkoutOrder.setOrderType(OrderType.order.getCode());
			checkoutOrder.setCashAmount(params.getCashAmount());
			checkoutOrder.setThirdPayType(params.getThirdPayType());
			checkoutOrder.setIsMultiPay(params.getIsMultiPay());
			checkoutOrder.setReceiptUserName(params.getReceiptUserName());
			checkoutOrder.setPayCoinScaleType(coinScaleType);
			checkoutOrder.setPayCoinScale(tokenPercent);
			checkoutOrder.setCoinToCnyPrice(t2cPrice);
			checkoutOrder.setPayUserName(params.getPayUserName());
			checkoutOrder.setThirdPayStatus(BusinessEnum.OrderStatus.PAYING.getCode());
		}

		params.setCheckoutOrderId(checkoutOrder.getOrderId());

		Byte orderStatus = checkoutOrder.getOrderStatus();
		ResponseData<String> payReturnData = null;
		OrderPayResult orderPayResult = new OrderPayResult();
		params.setCashAmount(checkoutOrder.getCashAmount());
		params.setAmount(checkoutOrder.getOrderMoney());
		if(!orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())
				&& checkoutOrder.getOrderMoney().compareTo(BigDecimal.ZERO) ==1){
			try {
				payReturnData = payService.pay(params);
			}catch(Exception e) {
				if(payReturnData != null){
					orderPayResult.setResultInfo(payReturnData.getMessage());
				}
				logger.error("",e);
			}
		}else {
			payReturnData = BusinessResponseFactory.createSuccess(null);
		}

		Byte isMultiPay = params.getIsMultiPay();
		Byte thirdPayType = params.getThirdPayType();
		try {
			Byte tokenStatus = null;
			Byte thirdPayStatus = null;
			if((payReturnData != null && payReturnData.isSuccessful()) || (payReturnData != null && payReturnData.getCode().equals(BusinessExceptionCode.REPETITIVE_DATA_PROCESSING))
					||orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())) {

				AsyncPayResult asyncPayResult = new AsyncPayResult();
				asyncPayResult.setOutTradeNo(checkoutOrder.getOrderFlowCode());
				asyncPayResult.setPayUserId(userId);
				asyncPayResult.setReceiptUserId(checkoutOrder.getReceiptUserId());
				asyncPayResult.setResultCode(ResultCode.SUCCESS.getCode());
				asyncPayResult.setAppId(checkoutOrder.getAppId());
				asyncPayResult.setSignCharset("utf-8");
				asyncPayResult.setSignType("RSA2");
				asyncPayResult.setTimestamp(DateTimeUtils.parseDateToString(new Date(),DateTimeUtils.PATTEN_YYYY_MM_DD_HH_MM_SS));
				asyncPayResult.setVersion("1.0");
				PayCallbackMsg<AsyncPayResult> payCallbackMsg = new PayCallbackMsg<AsyncPayResult>();
				payCallbackMsg.setNotifyUrl(params.getNotifyUrl());
				payCallbackMsg.setOutTradeNo(checkoutOrder.getOrderFlowCode());
				payCallbackMsg.setPayUserId(checkoutOrder.getPayUserId());
				payCallbackMsg.setReceiptUserId(checkoutOrder.getReceiptUserId());
				payCallbackMsg.setPrepayId(params.getPrepayId());
				asyncPayResult.setTradeNo(payReturnData.getData());
				tokenStatus = BusinessEnum.OrderStatus.PAID.getCode();
				orderPayResult.setResultCode(ResultCode.SUCCESS.getCode());
				asyncPayResult.setResultCode(ResultCode.SUCCESS.getCode());
				payCallbackMsg.setResult(asyncPayResult);
				if(params.getCashAmount().compareTo(BigDecimal.ZERO) == 1){
					redisTemplate.opsForValue().set(checkoutOrder.getOrderId(),JSON.toJSONString(payCallbackMsg));
					if(thirdPayType.equals(CashPayType.WX.getCode())){
					    orderPayResult.setThirdPayType(thirdPayType);
					    try {
						    Byte clientType = params.getClientType();
						    if(clientType.equals(ClientType.APP.getCode())){
							    WXAppReqParam wxAppReqParam = wxPayService.AppWxPay(params);
							    orderPayResult.setWxAppReqParam(wxAppReqParam);
						    } else if(clientType.equals(ClientType.H5.getCode())){
							    String codeUrl = wxPayService.h5WxPay(params);
							    orderPayResult.setWxCodeUrl(codeUrl);
						    }
						    asyncPayResult.setResultCode(ResultCode.SUCCESS.getCode());
						    thirdPayStatus = BusinessEnum.OrderStatus.PAYING.getCode();
					    } catch (Exception e) {
					    	logger.error("",e);
						    orderPayResult.setThirdPayResultCode(ResultCode.FAIL.getCode());
						    asyncPayResult.setResultCode(ResultCode.FAIL.getCode());
						    thirdPayStatus = BusinessEnum.OrderStatus.FAIL.getCode();
					    }
				    }
				} else {
					String sign = SignUtils.sign256(MD5.getMD5Sign(asyncPayResult),privateKey);
					asyncPayResult.setSign(sign);
					producer.send(MQContants.TOPIC_ORDER_RECEIVE,null, JSON.toJSONString(payCallbackMsg),userId+"-"+params.getOrderId());
					producer.send(MQContants.TOPIC_PAY_CALLBACK,null,JSON.toJSONString(payCallbackMsg),userId+"-"+params.getOrderId());
					thirdPayStatus = BusinessEnum.OrderStatus.PAYING.getCode();
				}
			}else if(payReturnData == null){
				tokenStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				thirdPayStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				orderPayResult.setResultCode(ResultCode.FAIL.getCode());
				orderPayResult.setResultInfo(BusinessExceptionCode.getMessage(BusinessExceptionCode.SYSTEM_ERROR));
			}else {
				tokenStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				thirdPayStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				orderPayResult.setResultCode(ResultCode.FAIL.getCode());
				orderPayResult.setResultInfo(payReturnData.getMessage());
			}
		}catch(Exception e) {
			logger.error("",e);
		}
		return orderPayResult;
	}


	public RefundReturnData refund(RefundRequestParams params) {
		CheckoutOrder checkoutOrder = checkoutOrderMapper.selectOrderByOrderIdAndUserId(params.getUserId(), params.getOrderId());
		TreeMap<String, String> signMap = new TreeMap<>();
		signMap.put("transactionId",params.getOrderId());
		signMap.put("amount",params.getAmount().toString());
		signMap.put("userId",params.getUserId());
		signMap.put("notifyUrl",params.getNotifyUrl());
		RefundReturnData returnData = new RefundReturnData();
		returnData.setNotifyUrl(params.getNotifyUrl());
		returnData.setUserId(params.getUserId());
		returnData.setRefundMoney(params.getAmount());
		returnData.setAttach(params.getAttach());
		returnData.setSign("");
		UserData user = userService.getUserById(params.getUserId()).getData();
		if(user == null){
			throw new BusinessException(BusinessExceptionCode.USER_IS_NOT_EXIST);
		}
		if(checkoutOrder == null){
			throw new BusinessException(BusinessExceptionCode.CHECKOUT_ORDER_ID_ERROR);
		}
		Byte orderStatus = checkoutOrder.getOrderStatus();
		Byte thirdPayStatus = checkoutOrder.getThirdPayStatus();
		if(!orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())) {
			throw new BusinessException(BusinessExceptionCode.REFUND_STATUS_ERROR);
		}

		if(totalZat.compareTo(refund.add(token)) < 0){
			throw new BusinessException(BusinessExceptionCode.REFUND_AMOUNT_MORETHAN_ORDER_AMOUNT);
		}
		BigDecimal cashAmountPaid = checkoutOrder.getCashAmount();
		BigDecimal refundCash = thirdPayRecordService.refundAmountByOrderId(checkoutOrder.getOrderId());
		ResponseData<String> refundRsp = null;
		String refundId = null;
		String coinType = checkoutOrder.getCoinType();
		Byte isMultiPay = checkoutOrder.getIsMultiPay();
		if(MultiPay.TRUE.getCode().equals(isMultiPay)){
			coinType = checkoutOrder.getMixCoinType();
		}
		if(token.compareTo(BigDecimal.ZERO) ==1 ){
			RefundParams refundParams = new RefundParams();
			refundParams.setCoinType(coinType);
			refundParams.setAmount(token);
			refundParams.setReceiveUserName(checkoutOrder.getPayUserName());
			refundParams.setRefundUserName(checkoutOrder.getReceiptUserName());
			try {
				refundRsp = payService.refund(refundParams);
				if(refundRsp != null){
				    refundId = refundRsp.getData();
				}
			} catch (Exception e) {
				if(refundRsp != null){
					returnData.setResultInfo(refundRsp.getMessage());
				}
			}
		}
		ReceiveMsg receiveMsg = new ReceiveMsg();
		Byte thirdPayType = checkoutOrder.getThirdPayType();
		if(refundRsp !=null && refundRsp.isSuccessful() || token.compareTo(BigDecimal.ZERO) == 0){
			if(totalZat.compareTo(refund.add(token)) < 1){
				orderStatus = BusinessEnum.OrderStatus.REFUND.getCode();
			}
			if(cashMoney.compareTo(BigDecimal.ZERO) ==1 && thirdPayStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())){
				if(cashAmountPaid.compareTo(refundCash.add(cashMoney)) < 0){
					throw new BusinessException(BusinessExceptionCode.REFUND_AMOUNT_MORETHAN_ORDER_AMOUNT);
				}

				if(thirdPayType.equals(CashPayType.WX.getCode())){
					try {
						refundId = wxPayService.wxRefund(checkoutOrder.getOrderId(), cashMoney);
						returnData.setResultCode(ResultCode.SUCCESS.getCode());
						if(cashAmountPaid != null && cashAmountPaid.compareTo(refundCash.add(cashMoney)) < 1){
							thirdPayStatus = BusinessEnum.OrderStatus.REFUND.getCode();
						}
					} catch (Exception e) {
						logger.error("wxpay refund error",e);
						returnData.setResultCode(ResultCode.FAIL.getCode());
					}
				} else if(thirdPayType.equals(CashPayType.ALIPAY.getCode())){
				    
				}
			} else {
				returnData.setResultCode(ResultCode.SUCCESS.getCode());
				producer.send(MQContants.TOPIC_REFUND_CALLBACK,null, JSON.toJSONString(returnData),params.getUserId()+"-"+params.getOrderId());
			}
		} else if(refundRsp == null){
			orderStatus = BusinessEnum.OrderStatus.REFUND_UNKOWN_ERROR.getCode();
			thirdPayStatus = BusinessEnum.OrderStatus.REFUND_UNKOWN_ERROR.getCode();
		} else {
			returnData.setResultCode(ResultCode.FAIL.getCode());
			producer.send(MQContants.TOPIC_REFUND_CALLBACK,null, JSON.toJSONString(returnData),params.getUserId()+"-"+params.getOrderId());
		}
		return returnData;
	}
	
	private BigDecimal mixedPaymentCoinToOtherCoin(String userId,BigDecimal total,String coinType) {
		CheckoutConfig config = checkoutConfigService.selectConfigByUserId(userId,coinType);
		if(config == null) {
			return total;
		}
		Byte type = config.getPayCoinScaleType();
		BigDecimal coinToCnyPrice = config.getCoinToCnyPrice();
		
		return total.multiply(coinToCnyPrice);
	}


	@Override
	public PayMoney computeMoney(String userId,BigDecimal total,String coinType) {
		CheckoutConfig config = checkoutConfigService.selectConfigByUserId(userId,coinType);
		if(config == null) {
			return null;
		}
		Byte type = config.getPayCoinScaleType();
		BigDecimal payCoinScale = config.getPayCoinScale();
		BigDecimal coinToCnyPrice = config.getCoinToCnyPrice();
		PayMoney payMoney = compute(total, type, payCoinScale, coinToCnyPrice);
		return payMoney;
	}

	private PayMoney compute(BigDecimal total, Byte type, BigDecimal payCoinScale, BigDecimal coinToCnyPrice) {
		PayMoney payMoney = new PayMoney();
		if(type.equals(CoinScaleType.FIXED.getCode())){
			BigDecimal fixed = payCoinScale;
			if(total.compareTo(fixed) <= 0){
			    fixed = total;
			}
			BigDecimal k = total.subtract(fixed);
			BigDecimal c = fixed.multiply(BigDecimal.ONE.divide(coinToCnyPrice));
			payMoney.setCashMoney(k);
			payMoney.setToken(c);
		} else {
			BigDecimal c = total.multiply(BigDecimal.ONE.subtract(payCoinScale.divide(new BigDecimal(100)))).setScale(2, ROUND_HALF_UP);
			BigDecimal t = total.subtract(c).multiply(BigDecimal.ONE.divide(coinToCnyPrice,8,ROUND_HALF_UP));
			payMoney.setToken(t);
			payMoney.setCashMoney(c);
		}
		return payMoney;
	}
	private PayMoney calMoney(BigDecimal total,BigDecimal payCoinScale, BigDecimal coinToCnyPrice){
		PayMoney payMoney = new PayMoney();
		BigDecimal c = total.multiply(BigDecimal.ONE.subtract(payCoinScale.divide(new BigDecimal(100)))).setScale(2, ROUND_HALF_UP);
		BigDecimal t = total.subtract(c).multiply(BigDecimal.ONE.divide(coinToCnyPrice,8,ROUND_HALF_UP));
		payMoney.setToken(t);
		payMoney.setCashMoney(c);
		return payMoney;
	}

	@Override
	public CreateOrderResult createOrder(CreateOrderBizData params,String userId) {
		CreateOrderResult result = new CreateOrderResult();
		UserData userData = userService.getUserById(userId).getData();
		String coinType = params.getCoinType();
		String mixedCoinType = params.getMixedCoinType();
		Byte mixPay = MultiPay.FALSE.getCode();
		BigDecimal coinPrice = BigDecimal.ONE;
		
		if(!StringUtils.isEmpty(mixedCoinType)){
			CheckoutConfig checkoutConfig = checkoutConfigService.selectConfigByUserId(userData.getUserId(), params.getMixedCoinType());
			if(checkoutConfig == null){
				throw new BusinessException(BusinessExceptionCode.MERCHANT_SETTING_ERROR);
			}
			coinPrice = checkoutConfig.getCoinToCnyPrice();
			mixPay = MultiPay.TRUE.getCode();
		}
		CheckoutOrder order = checkoutOrderService.selectOrderByUserAndOrderId(userData.getUserId(), params.getOutTradeNo());
		String prepayId = null;
		String key = null;
		String timeoutExpress = params.getTimeoutExpress();
		Long expireTime = getExpireTime(timeoutExpress);
		if(order != null){
			key = RedisKeyConstants.PREPAY_ + order.getOrderId();
			prepayId = (String) redisTemplate.opsForValue().get(key);
			if(StringUtils.isEmpty(prepayId)){
				prepayId = UUIDUtils.getUuid();
			}
		} else {
			order = new CheckoutOrder();
			order.setOrderStatus(BusinessEnum.OrderStatus.PAYING.getCode());
			order.setCoinNetworkType(params.getCoinNetworkType());
			order.setCoinType(params.getCoinType());

			order.setOrderFlowCode(params.getOutTradeNo());
			order.setOrderType(OrderType.order.getCode());

			order.setIsMultiPay(mixPay);
			order.setReceiptUserName(userData.getUserName());
			order.setAppId(params.getAppId());
			order.setOrderName(params.getSubject());
			order.setOrderMemo(params.getBody());

			order.setCoinToCnyPrice(coinPrice);
			String notifyUrl = params.getNotifyUrl();
			order.setNotifyUrl(notifyUrl);
			order.setOriginalPrice(new BigDecimal(params.getCoinAmount()));
			checkoutOrderMapper.insertSelective(order);
		}
		redisTemplate.opsForValue().set(key,prepayId);
		redisTemplate.opsForValue().set(RedisKeyConstants.PREPAY_+prepayId,order.getOrderId());
		redisTemplate.expire(key,expireTime,TimeUnit.SECONDS);
		redisTemplate.expire(RedisKeyConstants.PREPAY_+prepayId,expireTime,TimeUnit.SECONDS);
		result.setOutTradeNo(order.getOrderFlowCode());
		result.setPrepayId(prepayId);
		result.setTradeNo(order.getOrderId());
		result.setAppId(params.getAppId());
		result.setSignCharset(params.getSignCharset());
		result.setSignType(params.getSignType());
		result.setTimestamp(DateTimeUtils.parseDateToString(new Date(),DateTimeUtils.PATTEN_YYYY_MM_DD_HH_MM_SS));
		result.setVersion("1.0");
		String sign = SignUtils.sign(MD5.getMD5Sign(result), privateKey);
		result.setSign(sign);
		return result;
	}
	public Long getExpireTime(String str){
		Long ext = null;
		if(str.contains("m")){
			String m = str.replace("m", "");
			ext = new BigDecimal(m).multiply(new BigDecimal(60)).longValue();
		} else if(str.contains("d")){
			String m = str.replace("d", "");
			ext = new BigDecimal(m).multiply(new BigDecimal(24)).multiply(new BigDecimal(3600)).longValue();
		} else if(str.contains("h")){
			String m = str.replace("h", "");
			ext = new BigDecimal(m).multiply(new BigDecimal(3600)).longValue();
		} else if(str.contains("c")){
			Date date = DateTimeUtils.endOfTodDay(new Date());
			ext = (System.currentTimeMillis()-date.getTime())/1000;
		}
		return ext;
	}
	@Override
	public PayMoney computeMoney(String orderId) {
		PayMoney payMoney = new PayMoney();
		CheckoutOrder checkoutOrder = checkoutOrderMapper.selectByPrimaryKey(orderId);
		Byte isMultiPay = checkoutOrder.getIsMultiPay();
		if(BusinessEnum.OrderStatus.PAID.getCode().equals(checkoutOrder.getOrderStatus())){
		    payMoney.setToken(checkoutOrder.getOrderMoney());
		    payMoney.setCashMoney(checkoutOrder.getCashAmount());
		    payMoney.setMainCoinType(checkoutOrder.getCoinType());
		    payMoney.setSecondaryCoinType(checkoutOrder.getMixCoinType());
		} else {
			if(MultiPay.TRUE.getCode().equals(isMultiPay)){
				CheckoutConfig config = checkoutConfigService.selectConfigByUserId(checkoutOrder.getReceiptUserId(),checkoutOrder.getMixCoinType());
				payMoney = compute(checkoutOrder.getOriginalPrice(), config.getPayCoinScaleType(), config.getPayCoinScale(), config.getCoinToCnyPrice());
			} else {
				if(CurrencyEnum.CNY.getCode().equals(checkoutOrder.getCoinType())){
					payMoney.setCashMoney(checkoutOrder.getOriginalPrice());
					payMoney.setSecondaryCoinType(checkoutOrder.getCoinType());
				}
			}
		}
		return payMoney;
	}

	@Override
	public OrderPayResult orderPayV2(PayParams params, String userId) {
		if(userId.equals(params.getReceiptUserId())){
		    throw new BusinessException(BusinessExceptionCode.ILLEGAL_PARAMS);
		}
		CheckoutOrder checkoutOrder = checkoutOrderMapper.selectByPrimaryKey(params.getCheckoutOrderId());
		Byte orderStatus = null;
		if (checkoutOrder == null) {
			throw new BusinessException(BusinessExceptionCode.PAY_INFO_ERROR);
		} else {
			orderStatus = checkoutOrder.getOrderStatus();
			if (checkoutOrder.getThirdPayStatus() != null && checkoutOrder.getThirdPayStatus().equals(BusinessEnum.OrderStatus.PAID.getCode()) &&
					orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())) {
				throw new BusinessException(BusinessExceptionCode.CHECKOUT_ORDER_ID_DUPLICATE);
			}
			if (orderStatus.equals(BusinessEnum.OrderStatus.PAY_UNKOWN_ERROR.getCode())) {
				throw new BusinessException(BusinessExceptionCode.PAY_PROCESSING);
			}
		}
		BigDecimal tokenAmount = params.getAmount();
		
		if(MultiPay.TRUE.getCode().equals(checkoutOrder.getIsMultiPay())){
			BigDecimal otherCoinNum = mixedPaymentCoinToOtherCoin(params.getReceiptUserId(), params.getAmount(), params.getCoinType());
			if (params.getOriginalPrice().subtract(params.getCashAmount().add(otherCoinNum)).compareTo(new BigDecimal(0.01)) == 1) {
				throw new BusinessException(BusinessExceptionCode.ERROR_AMOUNT_OF_MONEY);
			}
		} else {
			if(checkoutOrder.getOriginalPrice().compareTo(tokenAmount) != 0 ){
				throw new BusinessException(BusinessExceptionCode.ERROR_AMOUNT_OF_MONEY);
			}
		}
		checkoutOrder.setPayUserId(userId);
		checkoutOrder.setPayUserName(params.getPayUserName());
		checkoutOrder.setReceiptUserName(params.getReceiptUserName());
		checkoutOrder.setUpdateDate(new Date());

		ResponseData<String> payReturnData = null;
		if (!orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())
				&& tokenAmount.compareTo(BigDecimal.ZERO) == 1) {
			try {
				payReturnData = payService.pay(params);
			} catch (Exception e) {
				if (payReturnData != null) {
					orderPayResult.setResultInfo(payReturnData.getMessage());
				}
			}
		} else {
			payReturnData = BusinessResponseFactory.createSuccess(null);
		}
		Byte thirdPayType = params.getThirdPayType();
		try {
			BigDecimal originalPrice = params.getOriginalPrice();
			BigDecimal cashPercent = cashAmount. divide(originalPrice, 8, ROUND_HALF_UP);
			BigDecimal tokenPercent = BigDecimal.ONE.subtract(cashPercent).multiply(new BigDecimal(100));
			checkoutOrder.setOrderMoney(params.getAmount());
			checkoutOrder.setCashAmount(cashAmount);
			checkoutOrder.setPayCoinScale(tokenPercent);
			Byte tokenStatus = null;
			Byte thirdPayStatus = null;
			if ((payReturnData != null && payReturnData.isSuccessful()) || (payReturnData != null && payReturnData.getCode().equals(BusinessExceptionCode.REPETITIVE_DATA_PROCESSING))
					|| orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())) {
				AsyncPayResult asyncPayResult = new AsyncPayResult();
				asyncPayResult.setOutTradeNo(checkoutOrder.getOrderFlowCode());
				asyncPayResult.setPayUserId(userId);
				asyncPayResult.setReceiptUserId(checkoutOrder.getReceiptUserId());
				asyncPayResult.setResultCode(ResultCode.SUCCESS.getCode());
				asyncPayResult.setAppId(checkoutOrder.getAppId());
				asyncPayResult.setSignCharset("utf-8");
				asyncPayResult.setSignType("RSA2");
				asyncPayResult.setTimestamp(DateTimeUtils.parseDateToString(new Date(),DateTimeUtils.PATTEN_YYYY_MM_DD_HH_MM_SS));
				asyncPayResult.setVersion("1.0");
				PayCallbackMsg<AsyncPayResult> payCallbackMsg = new PayCallbackMsg<AsyncPayResult>();
				payCallbackMsg.setResult(asyncPayResult);
				payCallbackMsg.setNotifyUrl(params.getNotifyUrl());
				payCallbackMsg.setOutTradeNo(checkoutOrder.getOrderFlowCode());
				payCallbackMsg.setPayUserId(checkoutOrder.getPayUserId());
				payCallbackMsg.setReceiptUserId(checkoutOrder.getReceiptUserId());
				payCallbackMsg.setPrepayId(params.getPrepayId());
				asyncPayResult.setTradeNo(payReturnData.getData());
				orderPayResult.setResultCode(ResultCode.SUCCESS.getCode());
				checkoutOrder.setPaymentDate(new Date());
				if (params.getCashAmount().compareTo(BigDecimal.ZERO) == 1) {
					if (thirdPayType.equals(CashPayType.WX.getCode())) {
						orderPayResult.setThirdPayType(thirdPayType);
						try {
							Byte clientType = params.getClientType();
							if (clientType.equals(ClientType.APP.getCode())) {
								WXAppReqParam wxAppReqParam = wxPayService.AppWxPay(params);
								orderPayResult.setWxAppReqParam(wxAppReqParam);
							} else if (clientType.equals(ClientType.H5.getCode())) {
								String codeUrl = wxPayService.h5WxPay(params);
								orderPayResult.setWxCodeUrl(codeUrl);
							}
							checkoutOrder.setThirdPayType(params.getThirdPayType());
							asyncPayResult.setResultCode(ResultCode.SUCCESS.getCode());
							thirdPayStatus = BusinessEnum.OrderStatus.PAYING.getCode();
						} catch (Exception e) {
							logger.error("", e);
							orderPayResult.setThirdPayResultCode(ResultCode.FAIL.getCode());
							asyncPayResult.setResultCode(ResultCode.FAIL.getCode());
							thirdPayStatus = BusinessEnum.OrderStatus.FAIL.getCode();
						}
					}
				} else {
					String sign = SignUtils.sign256(MD5.getMD5Sign(asyncPayResult), privateKey);
					asyncPayResult.setSign(sign);
					payCallbackMsg.setResultCode(ResultCode.SUCCESS.getCode());
					producer.send(MQContants.TOPIC_ORDER_RECEIVE, null, JSON.toJSONString(payCallbackMsg), userId + "-" + params.getOrderId());
					producer.send(MQContants.TOPIC_PAY_CALLBACK, null, JSON.toJSONString(payCallbackMsg), userId + "-" + params.getOrderId());
					thirdPayStatus = BusinessEnum.OrderStatus.PAYING.getCode();
				}
				redisTemplate.opsForValue().set(checkoutOrder.getOrderId(), JSON.toJSONString(payCallbackMsg));
			} else if (payReturnData == null) {
				tokenStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				thirdPayStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				orderPayResult.setResultCode(ResultCode.FAIL.getCode());
				orderPayResult.setResultInfo(BusinessExceptionCode.getMessage(BusinessExceptionCode.SYSTEM_ERROR));
			} else {
				tokenStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				thirdPayStatus = BusinessEnum.OrderStatus.FAIL.getCode();
				orderPayResult.setResultCode(ResultCode.FAIL.getCode());
				orderPayResult.setResultInfo(payReturnData.getMessage());
			}
			checkoutOrder.setOrderStatus(tokenStatus);
			checkoutOrder.setThirdPayStatus(thirdPayStatus);
			checkoutOrderMapper.updateByPrimaryKeySelective(checkoutOrder);
		} catch(Exception e) {
			logger.error("",e);
		}
		return orderPayResult;
	}

	@Override
	public SignOrderPayResult signOrderPay(SignOrderPayReqParams params) {
		SignOrderPayResult result = new SignOrderPayResult();
		String payUserId = params.getPayUserId();
		String receiptUserId = params.getReceiptUserId();
		if(payUserId.equals(receiptUserId)){
			throw new BusinessException(BusinessExceptionCode.ILLEGAL_PARAMS);
		}
		String outTradeNo = params.getOutTradeNo();
		UserData payUser = userService.getUserById(payUserId).getData();
		UserData rUser = userService.getUserById(receiptUserId).getData();
		BigDecimal coinAmount = new BigDecimal(params.getCoinAmount());
		String orderId = UUIDUtils.getUuid();
		if(checkoutOrder == null) {
			checkoutOrder = new CheckoutOrder();
			checkoutOrder.setOrderStatus(BusinessEnum.OrderStatus.PAYING.getCode());
			checkoutOrder.setPayUserId(payUserId);
			checkoutOrder.setUpdateDate(new Date());
			checkoutOrder.setOrderFlowCode(outTradeNo);
			checkoutOrder.setOrderType(OrderType.order.getCode());
			checkoutOrder.setReceiptUserName(rUser.getUserName());
			checkoutOrder.setPayUserName(payUser.getUserName());
			checkoutOrder.setThirdPayStatus(BusinessEnum.OrderStatus.PAYING.getCode());
			checkoutOrder.setAppId(params.getAppId());
			checkoutOrderMapper.insertSelective(checkoutOrder);
		}
		PayParams payParams = new PayParams();
		payParams.setCheckoutOrderId(checkoutOrder.getOrderId());
		payParams.setAmount(checkoutOrder.getOrderMoney());
		payParams.setReceiptUserName(checkoutOrder.getReceiptUserName());
		payParams.setPayUserName(checkoutOrder.getPayUserName());
		payParams.setPayPassword(params.getPayPassword());

		Byte orderStatus = checkoutOrder.getOrderStatus();
		ResponseData<String> payReturnData = null;
		if(!orderStatus.equals(BusinessEnum.OrderStatus.PAID.getCode())){
			try {
				payReturnData = payService.signPay(payParams,payUserId);
			}catch(Exception e) {
				logger.error("",e);
			}
		}
		result.setOutTradeno(outTradeNo);
		result.setPayUserId(payUserId);
		result.setReceiptUserId(receiptUserId);
		if ((payReturnData != null && payReturnData.isSuccessful())||
				(payReturnData != null && payReturnData.getCode().equals(BusinessExceptionCode.REPETITIVE_DATA_PROCESSING))){
			orderStatus = BusinessEnum.OrderStatus.PAID.getCode();
			result.setResultCode(ResultCode.SUCCESS.getCode());
			result.setTradeNo(payReturnData.getData());
			AsyncPayResult asyncPayResult = new AsyncPayResult();
			asyncPayResult.setOutTradeNo(outTradeNo);
			asyncPayResult.setPayUserId(payUserId);
			asyncPayResult.setTimestamp(DateTimeUtils.parseDateToString(new Date(),DateTimeUtils.PATTEN_YYYY_MM_DD_HH_MM_SS));
			asyncPayResult.setVersion("1.0");
			asyncPayResult.setSign(SignUtils.sign256(MD5.getMD5Sign(asyncPayResult),privateKey));
			PayCallbackMsg payCallbackMsg = new PayCallbackMsg();
			payCallbackMsg.setResult(asyncPayResult);
			payCallbackMsg.setNotifyUrl(params.getNotifyUrl());
			payCallbackMsg.setOutTradeNo(checkoutOrder.getOrderFlowCode());
			payCallbackMsg.setPayUserId(checkoutOrder.getPayUserId());
			payCallbackMsg.setReceiptUserId(checkoutOrder.getReceiptUserId());
			producer.send(MQContants.TOPIC_PAY_CALLBACK, null, JSON.toJSONString(payCallbackMsg), payUserId + "-" + outTradeNo);
		} else {
			result.setResultCode(ResultCode.FAIL.getCode());
			orderStatus = BusinessEnum.OrderStatus.FAIL.getCode();
		}
		result.setAppId(params.getAppId());
		result.setSignCharset(params.getSignCharset());
		result.setSignType(params.getSignType());
		result.setTimestamp(DateTimeUtils.parseDateToString(new Date(),DateTimeUtils.PATTEN_YYYY_MM_DD_HH_MM_SS));
		result.setVersion("1.0");
		String sign = SignUtils.sign256(MD5.getMD5Sign(result), privateKey);
		result.setSign(sign);
		checkoutOrder.setOrderStatus(orderStatus);
		checkoutOrderMapper.updateByPrimaryKeySelective(checkoutOrder);
		return result;
	}
}
