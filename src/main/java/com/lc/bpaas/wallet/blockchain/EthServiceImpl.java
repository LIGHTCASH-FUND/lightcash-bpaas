package com.lc.bpaas.wallet.blockchain;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.admin.Admin;
import org.web3j.protocol.admin.methods.response.PersonalUnlockAccount;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.utils.Convert;

import com.zatgo.zup.common.exception.BusinessException;
import com.zatgo.zup.common.exception.BusinessExceptionCode;
import com.zatgo.zup.wallet.blockchain.EthService;
import com.zatgo.zup.wallet.entity.AccountAddress;
import com.zatgo.zup.wallet.mapper.AccountAddressMapper;
import com.zatgo.zup.wallet.model.From;
import com.zatgo.zup.wallet.model.SendParams;
import com.zatgo.zup.wallet.model.To;
import com.zatgo.zup.wallet.model.TxRecord;

@Service
public class EthServiceImpl implements EthService {

	@Autowired
	private Web3j web3j;
	@Autowired
	private Admin admin;
	@Autowired
	private AccountAddressMapper accountAddressMapper;
	@Override
	public String getNewAddress(String password,String accountId) {
		String newAddress = null;
		try {
			newAddress = admin.personalNewAccount(password).send().getAccountId();
			AccountAddress accountAddress = new AccountAddress();
			accountAddress.setAccountId(accountId);
			accountAddress.setAddress(newAddress);
			accountAddressMapper.insert(accountAddress);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return newAddress;
	}

	@Override
	public String sendTransaction(SendParams params) {
		String transactionHash = null;
		Transaction transaction = Transaction.createEtherTransaction(params.getFrom(),
				null, null, null, params.getTo(), params.getAmount().toBigInteger());
		BigInteger unlockTime = BigInteger.valueOf(60L);
		try {
			PersonalUnlockAccount lock = admin.personalUnlockAccount(params.getFrom(), "123456", unlockTime).send();
			if(lock.accountUnlocked()){
				BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
				BigInteger amountUsed = web3j.ethEstimateGas(transaction).send().getAmountUsed();
				DefaultBlockParameter defaultBlockParameter = DefaultBlockParameter.valueOf("pending");
				BigInteger nonce = web3j.ethGetTransactionCount(params.getFrom(), defaultBlockParameter).send().getTransactionCount();
				transaction = Transaction.createEtherTransaction(params.getFrom(), nonce, gasPrice, amountUsed, params.getTo(), Convert.toWei(params.getAmount(), Convert.Unit.ETHER).toBigInteger());
				EthSendTransaction send = web3j.ethSendTransaction(transaction).send();
				Response.Error error = send.getError();
				if(error != null){
					throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,error.getMessage());
				}
				transactionHash = send.getTransactionHash();
			}
		} catch (Exception e) {
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,e.getMessage());
		}
		return transactionHash;
	}

	@Override
	public TxRecord getTransaction(String transactionHash) {
		TxRecord txRecord = new TxRecord();
		try {
			List<From> froms = new ArrayList<>();
			List<To> tos = new ArrayList<>();
			txRecord.setTos(tos);
			txRecord.setFroms(froms);
			EthTransaction send = web3j.ethGetTransactionByHash(transactionHash).send();
			if(send.getError() != null){
			    throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,send.getError().getMessage());
			}
			org.web3j.protocol.core.methods.response.Transaction transaction = send.getResult();
			From from = new From();
			from.setAddress(transaction.getFrom());
			from.setAmount(transaction.getValue().toString());
			froms.add(from);
			To to = new To();
			to.setAddress(transaction.getTo());
			to.setAmount(transaction.getValue().toString());
		} catch (Exception e) {
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,e.getMessage());
		}
		return txRecord;
	}

	@Override
	public BigDecimal getBalance(String address) {
		BigDecimal balance = null;
		try {
			EthGetBalance resp = web3j.ethGetBalance(address, DefaultBlockParameter.valueOf("latest")).send();
			Response.Error error = resp.getError();
			if(error != null){
				throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,error.getMessage());
			}
			BigInteger result = resp.getBalance();
			balance = Convert.fromWei(result.toString(), Convert.Unit.ETHER);
		} catch (Exception e) {
			throw new BusinessException(BusinessExceptionCode.SYSTEM_ERROR,e.getMessage());
		}
		return balance;
	}
}
