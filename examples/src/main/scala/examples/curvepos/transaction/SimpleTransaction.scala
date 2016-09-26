package examples.curvepos.transaction

import io.circe.syntax._
import scorex.core.transaction.NodeViewModifier.ModifierId
import com.google.common.primitives.Longs
import io.circe.Json
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.transaction.{NodeViewModifierCompanion, Transaction, TransactionChanges}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PublicKey25519

import scala.util.Try


sealed trait SimpleTransaction
  extends Transaction[PublicKey25519Proposition]


case class SimplePayment(sender: PublicKey25519,
                         recipient: PublicKey25519,
                         amount: Long,
                         fee: Long,
                         nonce: Long,
                         timestamp: Long)
  extends SimpleTransaction {

  override def json: Json = ???

  override lazy val messageToSign: Array[Byte] = id

  override type M = SimplePayment

  //todo: check statically or dynamically output size
  override lazy val id: ModifierId = FastCryptographicHash(
    sender.bytes ++
      recipient.bytes ++
      Longs.toByteArray(amount) ++
      Longs.toByteArray(fee) ++
      Longs.toByteArray(nonce) ++
      Longs.toByteArray(timestamp))

  override def companion: NodeViewModifierCompanion[SimplePayment] =
    new NodeViewModifierCompanion[SimplePayment] {
      override def bytes(modifier: SimplePayment): Array[Byte] = ???

      override def parse(bytes: Array[Byte]): Try[SimplePayment] = ???
    }
}