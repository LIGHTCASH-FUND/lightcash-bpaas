package com.lc.bpaas.pay;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.beanutils.BeanUtils;

public class CheckoutOrderServiceImpl {

	private static final Logger logger = LoggerFactory.getLogger(CheckoutOrderServiceImpl.class);

	@Autowired
	private CheckoutOrderMapper checkoutOrderMapper;
	@Autowired
	private OrderPayService orderPayService;
	@Override
	public CheckoutOrder selectOrderByUserAndOrderId(String userId, String orderId) {
		return checkoutOrderMapper.selectOrderByOrderIdAndUserId(userId,orderId);
	}
	@Override
	public PageInfo<CheckoutOrderData> selectOrderByPage(String userId,String pageNo, String pageSize) {
		PageHelper.startPage(Integer.valueOf(pageNo), Integer.valueOf(pageSize));
		Page<CheckoutOrder> pageData = checkoutOrderMapper.selectOrderByUserId(userId);
		
		List<CheckoutOrderData> list = new ArrayList<>();
		for(CheckoutOrder order:pageData) {
			CheckoutOrderData orderData = new CheckoutOrderData();
			try {
				BeanUtils.copyProperties(orderData, order);

				orderData.setCoinNetworkTypeEnum(BusinessEnum.NetWorkType.getEnumByType(order.getCoinNetworkType()));
				orderData.setMixCoinNetworkTypeEnum(BusinessEnum.NetWorkType.getEnumByType(order.getMixCoinNetworkType()));
				orderData.setCoinTypeEnum(BusinessEnum.CurrencyEnum.getEnumByType(order.getCoinType()));
				orderData.setMixCoinTypeEnum(BusinessEnum.CurrencyEnum.getEnumByType(order.getMixCoinType()));
				orderData.setOrderStatusEnum(BusinessEnum.OrderStatus.getEnumByType(order.getOrderStatus()));
				orderData.setThirdPayStatusEnum(BusinessEnum.OrderStatus.getEnumByType(order.getThirdPayStatus()));
				orderData.setThirdPayTypeEnum(BusinessEnum.ThirdPayType.getEnumByType(order.getThirdPayType()));
			} catch (Exception e) {
				logger.error("",e);
			}

			list.add(orderData);
		}
		
		
		PageInfo<CheckoutOrderData> pageInfoOrder =
				new PageInfo<CheckoutOrderData>(pageData.getPageNum(),pageData.getPageSize(),pageData.getTotal(),list);

		return pageInfoOrder;
	}

	@Override
	public List<CheckoutOrder> selectPayErrorOrder() {
		return null;
	}

	@Override
	public List<CheckoutOrder> selectRefundErrorOrder() {
		return null;
	}

	@Override
	public CheckoutOrder selectOrderByOrderIdAndPayUserId(String userId, String orderId) {
		return checkoutOrderMapper.selectOrderByOrderIdAndPayUserId(userId,orderId);
	}

	@Override
	public PrepayInfo selectOrderByPrePayId(String prepayId) {
		PrepayInfo prepayInfo = new PrepayInfo();
		String orderId = (String)redisTemplate.opsForValue().get(RedisKeyConstants.PREPAY_ + prepayId);
		if(StringUtils.isEmpty(orderId)){
		    throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR);
		}
		CheckoutOrder checkoutOrder = checkoutOrderMapper.selectByPrimaryKey(orderId);
		if(checkoutOrder == null){
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR);
		}
		PayMoney payMoney = orderPayService.computeMoney(checkoutOrder.getOrderId());
		prepayInfo.setOrder(checkoutOrder);
		prepayInfo.setPayMoney(payMoney);
		return prepayInfo;
	}

	@Override
	public Boolean checkPrepayId(String prepayId) {
		Boolean flag = false;
		String orderId = (String)redisTemplate.opsForValue().get(RedisKeyConstants.PREPAY_ + prepayId);
		if(!StringUtils.isEmpty(orderId)){
			flag = true;
		}
		return flag;
	}

	@Override
	public CheckoutOrder signOrderQuery(String userId, String id) {
		return checkoutOrderMapper.signOrderQuery(userId,id);
	}

	@Override
	public Page<CheckoutOrder> selectOrderByUserIdAndTime(String userId, String page, String size, String start, String end) {
		PageHelper.startPage(Integer.valueOf(page), Integer.valueOf(size));
		Page<CheckoutOrder> checkoutOrders = checkoutOrderMapper.signOrderQueryPage(userId, start, end);

		return checkoutOrders;
	}
}
