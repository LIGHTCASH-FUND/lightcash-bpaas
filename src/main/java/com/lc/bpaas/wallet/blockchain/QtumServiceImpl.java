package com.lc.bpaas.wallet.blockchain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.alibaba.fastjson.JSON;
import com.zatgo.zup.common.enumtype.BusinessEnum;
import com.zatgo.zup.common.exception.BusinessException;
import com.zatgo.zup.common.exception.BusinessExceptionCode;
import com.zatgo.zup.common.model.Commands;
import com.zatgo.zup.common.model.QtumTransaction;
import com.zatgo.zup.common.utils.ArgsUtils;
import com.zatgo.zup.common.utils.ContractMethodParameter;
import com.zatgo.zup.common.utils.ContractParamsBuilder;
import com.zatgo.zup.wallet.blockchain.QtumService;
import com.zatgo.zup.wallet.entity.IssuedCoinInfo;
import com.zatgo.zup.wallet.entity.SysParams;
import com.zatgo.zup.wallet.model.From;
import com.zatgo.zup.wallet.model.LocalTransaction;
import com.zatgo.zup.wallet.model.QtumTransactionDetail;
import com.zatgo.zup.wallet.model.QtumVin;
import com.zatgo.zup.wallet.model.QtumVout;
import com.zatgo.zup.wallet.model.SendParams;
import com.zatgo.zup.wallet.model.To;
import com.zatgo.zup.wallet.model.TxRecord;
import com.zatgo.zup.wallet.service.CoinInfoService;
import com.zatgo.zup.wallet.service.SystemParamsService;
import com.zatgo.zup.wallet.utils.JsonRpcClient;

@Service
public class QtumServiceImpl implements QtumService {
	private static final Logger logger = LoggerFactory.getLogger(QtumServiceImpl.class);
	@Autowired
	private JsonRpcClient jsonRpcClient;
	public final static String gasPrice  = "0.0000004";
	public final static Integer gasLimit = 120000;
	public final static String contractAddress  = "";
	public final static String transferhash  = "";
	@Value("${JsonRpcClient.passphrase}")
	private String walletPassphrase;
	@Value("${JsonRpcClient.unlockTime}")
	private int unlockTime;

	@Autowired
	private CoinInfoService coinInfoService;
	
	@Override
	public String getNewAddress() {
		String newAddress = null;
		try {
			IssuedCoinInfo coinInfo = coinInfoService.selectByCoinType(BusinessEnum.CurrencyEnum.ZAT.getCode());
			String mainAccount = coinInfo.getWalletMainAcct();
			newAddress = jsonRpcClient.invoke(Commands.GET_NEW_ADDRESS, ArgsUtils.asList(mainAccount), String.class);
		} catch (Throwable throwable) {
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,throwable.getMessage());
		}
		return newAddress;
	}

	@Override
	public String sendTransaction(SendParams params) {
		addressCheck(params.getTo());
		String txId = null;
		try {
			jsonRpcClient.invoke(Commands.WALLET_PASSPHRASE,ArgsUtils.asList(walletPassphrase,unlockTime),Object.class);
			txId = jsonRpcClient.invoke(Commands.SEND_FROM, ArgsUtils.asList(params.getFrom(),params.getTo(), params.getAmount(),1,params.getDesc()), String.class);
			if(StringUtils.isEmpty(txId)){
				throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR);
			}
		} catch (Throwable throwable) {
			logger.error("send qtum error",throwable.getMessage());
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,throwable.getMessage());
		} finally {
			try {
				jsonRpcClient.invoke(Commands.WALLET_LOCK,null,Object.class);
			} catch (Throwable throwable) {
				logger.error("lock wallet error");
			}
		}
		return txId;
	}

	@Override
	public String sendContractTransaction(SendParams params) {
		addressCheck(params.getTo());
		ContractParamsBuilder contractParamsBuilder = new ContractParamsBuilder();
		List<ContractMethodParameter> paraList = new ArrayList<>();
		ContractMethodParameter param2 = new ContractMethodParameter("_to", "address", params.getTo());
		ContractMethodParameter param3 = new ContractMethodParameter("_value", "uint256",params.getAmount().multiply(new BigDecimal(Math.pow(10,8))).toBigInteger().toString());
		paraList.add(param2);
		paraList.add(param3);
		String abiParams = contractParamsBuilder.createAbiMethodParams("transfer", paraList);
		try {
			jsonRpcClient.invoke(Commands.WALLET_PASSPHRASE,ArgsUtils.asList(walletPassphrase,unlockTime),Object.class);
			LinkedHashMap callcontract = jsonRpcClient.invoke(Commands.SENT_TO_CONTRACT, ArgsUtils.asList(contractAddress, abiParams,0,gasLimit,gasPrice,params.getFrom()), LinkedHashMap.class);
			String txId = (String)callcontract.get("txid");
			if(txId == null || txId.trim().equals("")) {
				logger.error(JSON.toJSONString(callcontract));
				throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR);
			}
			return txId;
		} catch (Throwable throwable) {
			logger.error("send zat error",throwable.getMessage());
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,throwable.getMessage());
		} finally {
			try {
				jsonRpcClient.invoke(Commands.WALLET_LOCK,null,Object.class);
			} catch (Throwable throwable) {
				logger.error("lock wallet error");
			}
		}
		
	}

	@Override
	public LocalTransaction listTransactions(String accountId) {
		try {
			ArrayList list  = jsonRpcClient.invoke(Commands.LIST_TRANSACTIONS, ArgsUtils.asList(accountId), ArrayList.class);
			System.out.println(list);
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		return null;
	}

	@Override
	public TxRecord getTransactionByTxId(String txId) {
		TxRecord txRecord = new TxRecord();
		List<From> froms = new ArrayList<>();
		List<To> tos = new ArrayList<>();
		txRecord.setFroms(froms);
		txRecord.setTos(tos);
		try {
			LinkedHashMap<String,Object> txResult = jsonRpcClient.invoke(Commands.GET_TRANSACTION, ArgsUtils.asList(txId), LinkedHashMap.class);
			txRecord.setBlockHash((String)txResult.get("blockhash"));
			txRecord.setTxId(txId);
			txRecord.setCreateTime((Integer) txResult.get("time"));
			String txhex = jsonRpcClient.invoke(Commands.GET_RAW_TRANSACTION, ArgsUtils.asList(txId), String.class);
			QtumTransactionDetail tx = jsonRpcClient.invoke(Commands.DECODE_RAW_TRANSACTION, ArgsUtils.asList(txhex), QtumTransactionDetail.class);
			List<QtumVin> vin = tx.getVin();
			List<QtumVout> vout = tx.getVout();
			BigDecimal toTotal = BigDecimal.ZERO;
			BigDecimal fromTotal = BigDecimal.ZERO;
			for (QtumVout qtumVout : vout) {
				List<String> addresses = qtumVout.getScriptPubKey().getAddresses();
				if(CollectionUtils.isEmpty(addresses)){
					continue;
				}
				To to = new To();
				to.setAddress(qtumVout.getScriptPubKey().getAddresses().get(0));
				to.setAmount(qtumVout.getValue().toString());
				toTotal.add(qtumVout.getValue());
				tos.add(to);
			}
			Map<String,BigDecimal> fromMap = new HashMap<>();
			for (QtumVin qtumVin : vin) {
				String txid = qtumVin.getTxid();
				String hex = jsonRpcClient.invoke(Commands.GET_RAW_TRANSACTION, ArgsUtils.asList(txid), String.class);
				QtumTransactionDetail _tx = jsonRpcClient.invoke(Commands.DECODE_RAW_TRANSACTION, ArgsUtils.asList(hex), QtumTransactionDetail.class);
				QtumVout qtumVout = _tx.getVout().get(qtumVin.getVout());
				BigDecimal value = qtumVout.getValue();
				fromTotal.add(value);
				String address = qtumVout.getScriptPubKey().getAddresses().get(0);
				if(fromMap.containsKey(address)){
				    fromMap.put(address,fromMap.get(address).add(value));
				} else {
					fromMap.put(address,value);
				}
			}
			for (Map.Entry<String, BigDecimal> entry : fromMap.entrySet()) {
				From from = new From();
				from.setAddress(entry.getKey());
				from.setAmount(entry.getValue().toString());
				froms.add(from);
			}
			txRecord.setFee(fromTotal.divide(toTotal).doubleValue());
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		return txRecord;

	}

	@Override
	public BigDecimal getTokenBalance(String address) {
		BigDecimal balance = null;
		try {
			ContractParamsBuilder contractParamsBuilder = new ContractParamsBuilder();
			List<ContractMethodParameter> paraList = new ArrayList<>();
			ContractMethodParameter contractMethodParameter = new ContractMethodParameter("_owner", "address", address);
			paraList.add(contractMethodParameter);
			String abiParams = contractParamsBuilder.createAbiMethodParams("balanceOf", paraList);
			LinkedHashMap<String,LinkedHashMap<String,String>> callcontract = jsonRpcClient.invoke(Commands.CALL_CONTRACT, ArgsUtils.asList(contractAddress,abiParams), LinkedHashMap.class);
			LinkedHashMap<String, String> executionResult = callcontract.get("executionResult");
			String output = executionResult.get("output");
			balance = new BigDecimal(Long.valueOf(output, 16)).divide(BigDecimal.valueOf(100000000));
		} catch (Exception e) {
			e.printStackTrace();
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		return balance;
	}

	@Override
	public BigDecimal getQtumBalance(String address) {
		BigDecimal balance = null;
		try {
			Double resp = jsonRpcClient.invoke(Commands.GET_BALANCE, ArgsUtils.asList(address), Double.class);
			balance = BigDecimal.valueOf(resp);
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		return balance;
	}

	@Override
	public TxRecord getTokenTransactionRecord(String id) {
		Map<String, String[]> address = new HashMap<>();
		Map<String,String[]> topic = new HashMap<>();
		TxRecord txRecord = new TxRecord();
		try {
			id = jsonRpcClient.invoke(Commands.GET_HEX_ADDRESS, ArgsUtils.asList(id), String.class);
			ContractParamsBuilder contractParamsBuilder = new ContractParamsBuilder();
			id = contractParamsBuilder.appendNumericPattern(id);
			address.put("addresses",new String[]{contractAddress});
			topic.put("topics",new String[]{transferhash,id});
			Integer blockCount = jsonRpcClient.invoke(Commands.GET_BLOCK_COUNT, null, Integer.class);
			ArrayList<LinkedHashMap> result = jsonRpcClient.invoke(Commands.SEARCH_LOGS, ArgsUtils.asList(blockCount, -1, address, topic), ArrayList.class);
			LinkedHashMap<String,ArrayList> linkedHashMap = result.get(result.size()-1);
			ArrayList<LinkedHashMap> log = linkedHashMap.get("log");
			LinkedHashMap<String,ArrayList<String>> linkedHashMap1 = log.get(1);
			ArrayList<String> topics = linkedHashMap.get("topics");
			String fromAddressHex = topics.get(1).substring(24);
			String toAddressHex = topics.get(2).substring(24);
			String fromAddress = jsonRpcClient.invoke(Commands.FROM_HEX_ADDRESS, ArgsUtils.asList(fromAddressHex), String.class);
			String toAddress = jsonRpcClient.invoke(Commands.FROM_HEX_ADDRESS, ArgsUtils.asList(toAddressHex), String.class);
			String data = (String) log.get(2).get("data");
			BigDecimal transferAmount = new BigDecimal(Long.valueOf(data, 16)).divide(BigDecimal.valueOf(100000000));
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		return null;
	}

	@Override
	public QtumTransaction getTransaction(String txId) {
		QtumTransaction qtumTransaction = null;
		try {
			Object invoke = jsonRpcClient.invoke(Commands.GET_TRANSACTION, ArgsUtils.asList(txId), Object.class);
			String json = JSON.toJSONString(invoke);
			qtumTransaction = JSON.parseObject(json, QtumTransaction.class);
			BigDecimal fee = qtumTransaction.getFee();
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}
		return qtumTransaction;
	}
	private void addressCheck(String address){
		try {
			String invoke = jsonRpcClient.invoke(Commands.GET_HEX_ADDRESS, ArgsUtils.asList(address), String.class);
		} catch (Throwable throwable) {
			throw new BusinessException(BusinessExceptionCode.INVALID_ADDRESS);
		}
	}

}
