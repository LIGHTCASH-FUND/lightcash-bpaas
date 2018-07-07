package com.lc.bpaas.wallet.blockchain;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.neemre.btcdcli4j.core.BitcoindException;
import com.neemre.btcdcli4j.core.CommunicationException;
import com.neemre.btcdcli4j.core.domain.Transaction;
import com.zatgo.zup.wallet.blockchain.BtcService;
import com.zatgo.zup.wallet.entity.AccountAddress;
import com.zatgo.zup.wallet.mapper.AccountAddressMapper;
import com.zatgo.zup.wallet.model.SendParams;
import com.zatgo.zup.wallet.utils.Btcd4j;

@Service
public class BtcServiceImpl implements BtcService {
	@Autowired
	private AccountAddressMapper accountAddressMapper;
	@Autowired
	private Btcd4j btcd4j;
	private String mainAccount;
	@Override
	public String getNewAddress(String account) {
		String newAddress = null;
		try {
			newAddress = btcd4j.getClient().getNewAddress(mainAccount);
			AccountAddress accountAddress = new AccountAddress();
			accountAddress.setAccountId(account);
			accountAddress.setAddress(newAddress);
			accountAddressMapper.insert(accountAddress);
		} catch (BitcoindException e) {
			e.printStackTrace();
		} catch (CommunicationException e) {
			e.printStackTrace();
		}
		return newAddress;
	}

	@Override
	public String sendTransaction(SendParams params) {
		String txId = null;
		try {
			txId = btcd4j.getClient().sendToAddress(params.getTo(), params.getAmount(), params.getDesc());
			Transaction transaction = btcd4j.getClient().getTransaction(txId);
		} catch (BitcoindException e) {
			e.printStackTrace();
		} catch (CommunicationException e) {
			e.printStackTrace();
		}
		return txId;
	}

	@Override
	public BigDecimal getBalance(String accountId) {
		BigDecimal balance = null;
		try {
			balance = btcd4j.getClient().getBalance(accountId);
		} catch (BitcoindException e) {
			e.printStackTrace();
		} catch (CommunicationException e) {
			e.printStackTrace();
		}
		return balance;
	}
}
