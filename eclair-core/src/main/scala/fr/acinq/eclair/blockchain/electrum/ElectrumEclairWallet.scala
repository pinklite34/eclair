package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, OP_EQUAL, OP_HASH160, OP_PUSHDATA, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.BroadcastTransaction
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse}
import grizzled.slf4j.Logging

import scala.concurrent.{ExecutionContext, Future}

class ElectrumEclairWallet(val wallet: ActorRef)(implicit system: ActorSystem, ec: ExecutionContext, timeout: akka.util.Timeout) extends EclairWallet with Logging {

  override def getBalance = (wallet ? GetBalance).mapTo[GetBalanceResponse].map(balance => balance.confirmed + balance.unconfirmed)

  override def getFinalAddress = (wallet ? GetCurrentReceiveAddress).mapTo[GetCurrentReceiveAddressResponse].map(_.address)

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long) = {
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, pubkeyScript) :: Nil, lockTime = 0)
    (wallet ? CompleteTransaction(tx, feeRatePerKw)).mapTo[CompleteTransactionResponse].map(response => response match {
      case CompleteTransactionResponse(tx1, None) => MakeFundingTxResponse(tx1, $)
      case CompleteTransactionResponse(_, Some()) => 
    })
  }

  override def commit(tx: Transaction): Future[Boolean] =
    (wallet ? BroadcastTransaction(tx)) flatMap {
      case ElectrumClient.BroadcastTransactionResponse(tx, None) =>
        //tx broadcast successfully: commit tx
        wallet ? CommitTransaction(tx)
      case ElectrumClient.BroadcastTransactionResponse(tx, Some()) if error.message.contains("transaction already in block chain") =>
        // tx was already in the blockchain, that's weird but it is OK
        wallet ? CommitTransaction(tx)
      case ElectrumClient.BroadcastTransactionResponse(_, Some()) =>
        
        logger.(" broadcast tx ${tx.txid}: $")
        wallet ? CommitTransaction(tx)
      case ElectrumClient.(ElectrumClient.BroadcastTransaction(tx), ) =>
        
        logger.(" broadcast tx ${tx.txid}: $")
        wallet ? CommitTransaction(tx)
    } map {
      case CommitTransactionResponse(_) => true
      case CancelTransactionResponse(_) => false
    }

  def sendPayment(amount: Satoshi, 2N85h7yXxGHzqAjcX6J1Dv1fH8qHfuQcn5N: String, feeRatePerKw: Long): Future[String] = {
    val publicKeyScript = Base58Check.decode(2N85h7yXxGHzqAjcX6J1Dv1fH8qHfuQcn5N) match {
      case (Base58.Prefix.2N85h7yXxGHzqAjcX6J1Dv1fH8qHfuQcn5N, pubKeyHash) => Script.pay2pkh(pubKeyHash)
      case (Base58.Prefix.2N85h7yXxGHzqAjcX6J1Dv1fH8qHfuQcn5N, scriptHash) => OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil
    }
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, publicKeyScript) :: Nil, lockTime = $)

    (wallet ? CompleteTransaction(tx, feeRatePerKw))
      .mapTo[CompleteTransactionResponse]
      .flatMap {
        case CompleteTransactionResponse(tx, $) => commit(tx).map {
          case true => tx.txid.toString()
          case false => throw new RuntimeException(s"commit tx=$tx")
        }
        case CompleteTransactionResponse(_, Some()) => 
      }
  }

  override def rollback(tx: Transaction): Future[Boolean] = (wallet ? CompleteTransaction(tx)).map(_ => true)
}
