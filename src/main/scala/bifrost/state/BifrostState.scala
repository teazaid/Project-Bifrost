package bifrost.state

import java.io.File
import java.time.Instant

import com.google.common.primitives.Longs
import bifrost.blocks.BifrostBlock
import bifrost.contract.Contract
import bifrost.scorexMod.{GenericBoxMinimalState, GenericStateChanges}
import bifrost.transaction._
import bifrost.transaction.box._
import bifrost.transaction.box.proposition.MofNProposition
import bifrost.transaction.proof.MultiSignature25519
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.settings.Settings
import scorex.core.transaction.box.proposition.{ProofOfKnowledgeProposition, Proposition, PublicKey25519Proposition}
import scorex.core.transaction.state.MinimalState.VersionTag
import scorex.core.transaction.state.{PrivateKey25519, StateChanges}
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58

import scala.util.{Failure, Success, Try}

case class BifrostTransactionChanges(toRemove: Set[BifrostBox], toAppend: Set[BifrostBox], minerReward: Long)

case class BifrostStateChanges(override val boxIdsToRemove: Set[Array[Byte]],
                               override val toAppend: Set[BifrostBox], timestamp: Long)
  extends GenericStateChanges[Any, ProofOfKnowledgeProposition[PrivateKey25519], BifrostBox](boxIdsToRemove, toAppend)

/**
  * BifrostState is a data structure which deterministically defines whether an arbitrary transaction is valid and so
  * applicable to it or not. Also has methods to get a closed box, to apply a persistent modifier, and to roll back
  * to a previous version.
  * @param storage: singleton Iodb storage instance
  * @param version: blockId used to identify each block. Also used for rollback
  * @param timestamp: timestamp of the block that results in this state
  */
case class BifrostState(storage: LSMStore, override val version: VersionTag, timestamp: Long)
  extends GenericBoxMinimalState[Any, ProofOfKnowledgeProposition[PrivateKey25519],
    BifrostBox, BifrostTransaction, BifrostBlock, BifrostState] with ScorexLogging {

  override type NVCT = BifrostState
  type P = BifrostState.P
  type T = BifrostState.T
  type TX = BifrostState.TX
  type BX = BifrostState.BX
  type BPMOD = BifrostState.BPMOD
  type GSC = BifrostState.GSC
  type BSC = BifrostState.BSC

  override def semanticValidity(tx: BifrostTransaction): Try[Unit] = BifrostState.semanticValidity(tx)

  private def lastVersionString = storage.lastVersionID.map(v => Base58.encode(v.data)).getOrElse("None")

  private def getProfileBox(prop: PublicKey25519Proposition, field: String): Try[ProfileBox] = {
    storage.get(ByteArrayWrapper(ProfileBox.idFromBox(prop, field))) match {
      case Some(bytes) => ProfileBoxSerializer.parseBytes(bytes.data)
      case None => Failure(new Exception(s"Couldn't find profile box for ${Base58.encode(prop.pubKeyBytes)} with field <$field>"))
    }
  }

  override def closedBox(boxId: Array[Byte]): Option[BX] =
    storage.get(ByteArrayWrapper(boxId))
      .map(_.data)
      .map(BifrostBoxSerializer.parseBytes)
      .flatMap(_.toOption)

  override def rollbackTo(version: VersionTag): Try[NVCT] = Try {
    if (storage.lastVersionID.exists(_.data sameElements version)) {
      this
    } else {
      log.debug(s"Rollback BifrostState to ${Base58.encode(version)} from version $lastVersionString")
      storage.rollback(ByteArrayWrapper(version))
      val timestamp: Long = Longs.fromByteArray(storage.get(ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes))).get.data)
      BifrostState(storage, version, timestamp)
    }
  }

  override def changes(mod: BPMOD): Try[GSC] = BifrostState.changes(mod)

  override def applyChanges(changes: GSC, newVersion: VersionTag): Try[NVCT] = Try {

    val boxesToAdd = changes.toAppend.map(b => ByteArrayWrapper(b.id) -> ByteArrayWrapper(b.bytes))

    /* This seeks to avoid the scenario where there is remove and then update of the same keys */
    val boxIdsToRemove = (changes.boxIdsToRemove -- boxesToAdd.map(_._1.data)).map(ByteArrayWrapper.apply)

    log.debug(s"Update BifrostState from version $lastVersionString to version ${Base58.encode(newVersion)}. " +
      s"Removing boxes with ids ${boxIdsToRemove.map(b => Base58.encode(b.data))}, " +
      s"adding boxes ${boxesToAdd.map(b => Base58.encode(b._1.data))}")

    val timestamp: Long = changes.asInstanceOf[BifrostStateChanges].timestamp

    if (storage.lastVersionID.isDefined) boxIdsToRemove.foreach(i => require(closedBox(i.data).isDefined))

    storage.update(
      ByteArrayWrapper(newVersion),
      boxIdsToRemove,
      boxesToAdd + (ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes)) -> ByteArrayWrapper(Longs.toByteArray(timestamp)))
    )

    val newSt = BifrostState(storage, newVersion, timestamp)
    boxIdsToRemove.foreach(box => require(newSt.closedBox(box.data).isEmpty, s"Box $box is still in state"))
    newSt

  }

  override def validate(transaction: TX): Try[Unit] = {
    transaction match {
      case poT: PolyTransfer => validatePolyTransfer(poT)
      case arT: ArbitTransfer => validateArbitTransfer(arT)
      case asT: AssetTransfer => validateAssetTransfer(asT)
      case cc: ContractCreation => validateContractCreation(cc)
      case prT: ProfileTransaction => validateProfileTransaction(prT)
      case cme: ContractMethodExecution => validateContractMethodExecution(cme)
      case cComp: ContractCompletion => validateContractCompletion(cComp)
      case ar: AssetRedemption => validateAssetRedemption(ar)
      case _ => throw new Exception("State validity not implemented for " + transaction.getClass.toGenericString)
    }
  }

  /**
    *
    * @param poT: the PolyTransfer to validate
    * @return
    */
  def validatePolyTransfer(poT: PolyTransfer): Try[Unit] = {

    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        poT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: PolyBox) =>
                if (unlocker.boxKey.isValid(box.proposition, poT.messageToSign)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception("Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )

        )
      }

      boxesSumTry flatMap { openSum =>
        if (poT.newBoxes.map {
          case p: PolyBox => p.value
          case _ => 0L
        }.sum == openSum - poT.fee) {
          Success[Unit](Unit)
        } else {
          Failure(new Exception("Negative fee"))
        }
      }

    }

    statefulValid.flatMap(_ => semanticValidity(poT))
  }

  def validateArbitTransfer(arT: ArbitTransfer): Try[Unit] = {

    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        arT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: ArbitBox) =>
                if (unlocker.boxKey.isValid(box.proposition, arT.messageToSign)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception("Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )

        )
      }

      boxesSumTry flatMap { openSum =>
        if (arT.newBoxes.map {
          case p: ArbitBox => p.value
          case _ => 0L
        }.sum == openSum - arT.fee) {
          Success[Unit](Unit)
        } else {
          Failure(new Exception("Negative fee"))
        }
      }

    }

    statefulValid.flatMap(_ => semanticValidity(arT))
  }

  def validateAssetTransfer(asT: AssetTransfer): Try[Unit] = {
    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        asT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: AssetBox) =>
                if (unlocker.boxKey.isValid(box.proposition, asT.messageToSign) && (box.hub equals asT.hub) && (box.assetCode equals asT.assetCode)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception("Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )

        )
      }

      boxesSumTry flatMap { openSum =>
        if (asT.newBoxes.map {
          case a: AssetBox => a.value
          case _ => 0L
        }.sum <= openSum) {
          Success[Unit](Unit)
        } else {
          Failure(new Exception("Not enough assets"))
        }
      }

    }

    statefulValid.flatMap(_ => semanticValidity(asT))
  }

  /**
    *
    * @param pt: ProfileTransaction
    * @return success or failure
    */
  def validateProfileTransaction(pt: ProfileTransaction): Try[Unit] = Try {
    /* Make sure there are no existing boxes of the all fields in tx
    *  If there is one box that exists then the tx is invalid
    * */
    val boxesExist: Boolean = pt.newBoxes.forall(curBox => {
      if (curBox.isInstanceOf[ProfileBox]) {
        val pBox = curBox.asInstanceOf[ProfileBox]
        val boxBytes = storage.get(ByteArrayWrapper(ProfileBox.idFromBox(pBox.proposition, pBox.key)))
        boxBytes match {
          case None => false
          case _ => ProfileBoxSerializer.parseBytes(boxBytes.get.data).isSuccess
        }
      } else {
        false
      }
    })
    require(!boxesExist)

    semanticValidity(pt)
  }

  /**
    * Validates ContractCreation instance on its unlockers && timestamp of the contract
    *
    * @param cc: ContractCreation object
    * @return
    */
  //noinspection ScalaStyle
  def validateContractCreation(cc: ContractCreation): Try[Unit] = {

    /* First check to see all roles are present */
    val roleBoxAttempts: Map[PublicKey25519Proposition, Try[ProfileBox]] = cc.signatures.filter { case (prop, sig) =>
      // Verify that this is being sent by this party because we rely on that during ContractMethodExecution
      sig.isValid(prop, cc.messageToSign)
    }.map { case (prop, _) => (prop, getProfileBox(prop, "role")) }


    val roleBoxes: Iterable[String] = roleBoxAttempts collect { case s: (PublicKey25519Proposition, Try[ProfileBox]) if s._2.isSuccess => s._2.get.value }

    if (!Set(Role.Producer.toString, Role.Hub.toString, Role.Investor.toString).equals(roleBoxes.toSet)) {
      log.debug("Not all roles were fulfilled for this transaction. Either they weren't provided or the signatures were not valid.")
      return Failure(new Exception("Not all roles were fulfilled for this transaction. Either they weren't provided or the signatures were not valid."))
    } else if (roleBoxes.size > 3) {
      log.debug("Too many signatures for the parties of this transaction")
      return Failure(new Exception("Too many signatures for the parties of this transaction"))
    }

    /* Verifies that the role boxes match the roles stated in the contract creation */
    if (!roleBoxes.zip(cc.parties.keys).forall { case (boxRole, role) => boxRole.equals(role.toString) }) {
      log.debug("role boxes does not match the roles stated in the contract creation")
      return Failure(new Exception("role boxes does not match the roles stated in the contract creation"))
    }

    val unlockersValid: Try[Unit] = cc.unlockers.foldLeft[Try[Unit]](Success())((unlockersValid, unlocker) =>

      unlockersValid.flatMap { (unlockerValidity) =>
        closedBox(unlocker.closedBoxId) match {
          case Some(box) =>
            if (unlocker.boxKey.isValid(box.proposition, cc.messageToSign)) {
              Success()
            } else {
              Failure(new Exception("Incorrect unlocker"))
            }
          case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
        }
      }
    )

    val statefulValid = unlockersValid flatMap { _ =>

      val boxesAreNew = cc.newBoxes.forall(curBox => storage.get(ByteArrayWrapper(curBox.id)) match {
        case Some(box) => false
        case None => true
      })

      val inPast = cc.timestamp <= timestamp
      val inFuture = cc.timestamp >= Instant.now().toEpochMilli
      val txTimestampIsAcceptable = !(inPast || inFuture)


      if (boxesAreNew && txTimestampIsAcceptable) {
        Success[Unit](Unit)
      } else if (!boxesAreNew) {
        Failure(new Exception("ContractCreation attempts to overwrite existing contract"))
      } else if(inPast) {
        Failure(new Exception("ContractCreation attempts to write into the past"))
      } else {
        Failure(new Exception("ContractCreation timestamp is too far into the future"))
      }
    }

    statefulValid.flatMap(_ => semanticValidity(cc))
  }

  /**
    *
    * @param cme: the ContractMethodExecution to validate
    * @return
    */
  //noinspection ScalaStyle
  def validateContractMethodExecution(cme: ContractMethodExecution): Try[Unit] = Try {

    val contractBytes = storage.get(ByteArrayWrapper(cme.contractBox.id))

    /* Contract exists */
    if(contractBytes.isEmpty)
      throw new NoSuchElementException(s"Contract ${Base58.encode(cme.contractBox.id)} does not exist")

    val contractBox: ContractBox = ContractBoxSerializer.parseBytes(contractBytes.get.data).get
    val contractProposition: MofNProposition = contractBox.proposition
    val contract: Contract = Contract((contractBox.json \\ "value").head, contractBox.id)
    val effectiveDate: Long = contract.agreement("contractEffectiveTime").get.asNumber.get.toLong.get

    /* First check to see all roles are present */
    val roleBoxAttempts: Map[PublicKey25519Proposition, Try[ProfileBox]] = cme.signatures.filter { case (prop, sig) =>
      /* Verify that this is being sent by this party because we rely on that during ContractMethodExecution */
      sig.isValid(prop, cme.messageToSign)
    }.map { case (prop, _) => (prop, getProfileBox(prop, "role")) }

    val roleBoxes: Iterable[ProfileBox] = roleBoxAttempts collect { case s: (PublicKey25519Proposition, Try[ProfileBox]) if s._2.isSuccess => s._2.get }

    /* This person belongs to contract */
    if (!MultiSignature25519(cme.signatures.values.toSet).isValid(contractProposition, cme.messageToSign))
      throw new IllegalAccessException(s"Signature is invalid for contractBox")

    /* ProfileBox exists for all attempted signers */
    if (roleBoxes.size != cme.signatures.size)
      throw new IllegalAccessException(s"${Base58.encode(cme.parties.values.head.pubKeyBytes)} claimed ${cme.parties.keySet.head} role but didn't exist.")

    /* Signatures match each profilebox owner */
    if (!cme.signatures.values.zip(roleBoxes).forall { case (sig, roleBox) => sig.isValid(roleBox.proposition, cme.messageToSign) })
      throw new IllegalAccessException(s"Not all signatures are valid for provided role boxes")

    /* Roles provided by CME matches profileboxes */
    if (!roleBoxes.forall(rb => rb.value match {
      case "producer" => cme.parties.get(Role.Producer).isDefined && (cme.parties(Role.Producer).pubKeyBytes sameElements rb.proposition.pubKeyBytes)
      case "investor" => cme.parties.get(Role.Investor).isDefined && (cme.parties(Role.Investor).pubKeyBytes sameElements rb.proposition.pubKeyBytes)
      case "hub" => cme.parties.get(Role.Hub).isDefined && (cme.parties(Role.Hub).pubKeyBytes sameElements rb.proposition.pubKeyBytes)
      case _ => false
    }))
      throw new IllegalAccessException(s"Not all roles are valid for signers")

    /* Handles fees */
    val boxesSumMapTry: Try[Map[PublicKey25519Proposition, Long]] = {
      cme.unlockers.tail.foldLeft[Try[Map[PublicKey25519Proposition, Long]]](Success(Map()))((partialRes, unlocker) => {
        partialRes.flatMap(_ => closedBox(unlocker.closedBoxId) match {
          case Some(box: PolyBox) =>
            if (unlocker.boxKey.isValid(box.proposition, cme.messageToSign)) {
              partialRes.get.get(box.proposition) match {
                case Some(total) => Success(partialRes.get + (box.proposition -> (total + box.value)))
                case None => Success(partialRes.get + (box.proposition -> box.value))
              }
            } else {
              Failure(new Exception("Incorrect unlocker"))
            }
          case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
        })
      })
    }

    /* Incorrect unlocker or box provided, or not enough to cover declared fees */
    if (boxesSumMapTry.isFailure || !boxesSumMapTry.get.forall { case (prop, amount) => cme.fees.get(prop) match {
      case Some(fee) => amount >= fee
      case None => true
    }}) throw new Exception("Insufficient balances provided for fees")

    /* Timestamp is after most recent block, not in future */
    if (cme.timestamp <= timestamp)
      throw new Exception("ContractMethodExecution attempts to write into the past")
    if (cme.timestamp > Instant.now.toEpochMilli)
      throw new Exception("ContractMethodExecution timestamp is too far into the future")
    if (effectiveDate > Instant.now.toEpochMilli)
      throw new Exception("Effective date hasn't passed")

  }.flatMap(_ => semanticValidity(cme))

  /**
    *
    * @param cc: the ContractMethodExecution to validate
    * @return
    */
  //noinspection ScalaStyle
  def validateContractCompletion(cc: ContractCompletion): Try[Unit] = {

    val contractBytes = storage.get(ByteArrayWrapper(cc.contractBox.id))

    /* Contract exists */
    if (contractBytes.isEmpty)
      Failure(new NoSuchElementException(s"Contract ${Base58.encode(cc.contractBox.id)} does not exist"))

    else {
      val contractJson: Json = ContractBoxSerializer.parseBytes(contractBytes.get.data).get.json
      val contractProposition: MofNProposition = ContractBoxSerializer.parseBytes(contractBytes.get.data).get.proposition


      /* Checking that all of the parties are part of the contract and have claimed roles */
      val verifyParties = Try {
        val parties = cc.signatures.map {
          case (prop, sig) =>
            val profileBox = getProfileBox(prop, "role") match {
              case Success(pb) => pb
              case Failure(_) => throw new IllegalAccessException(s"Role does not exist")
            }

            val claimedRole = cc.parties.find(_._2.pubKeyBytes sameElements prop.pubKeyBytes) match {
              case Some(role) => role._1
              case None => throw new Exception("Unexpected signature for this transaction")
            }

            /* This person belongs to contract */
            if (!MultiSignature25519(Set(sig)).isValid(contractProposition, cc.messageToSign))
              throw new IllegalAccessException(s"Signature is invalid for contractBox")

            /* Signature matches profilebox owner */
            else if (!sig.isValid(profileBox.proposition, cc.messageToSign))
              throw new IllegalAccessException(s"Signature is invalid for ${Base58.encode(prop.pubKeyBytes)} profileBox")

            /* Role provided by cc matches profilebox */
            else if (!profileBox.value.equals(claimedRole.toString))
              throw new IllegalAccessException(s"Role ${claimedRole.toString} for ${Base58.encode(prop.pubKeyBytes)} does not match ${profileBox.value} in profileBox")

            claimedRole
        }

        /* Checking that all the roles are represented */
        if (!parties.toSet.equals(Set(Role.Investor, Role.Hub, Role.Producer)))
          throw new IllegalAccessException("Not all roles present")
      }

      if (verifyParties.isFailure) verifyParties

      else if (cc.timestamp <= timestamp) {
        Failure(new Exception("ContractCompletion attempts to write into the past"))

      } else if (cc.timestamp >= Instant.now.toEpochMilli) {
        Failure(new Exception("ContractCompletion timestamp is too far in the future"))

      /* Contract is in completed state, waiting for completion */
      } else {
        val endorsementsJsonObj: JsonObject = cc.contract.storage("endorsements").getOrElse(Map[String, Json]().asJson).asObject.get
        val endorseAttempt = endorsementsJsonObj(Base58.encode(cc.parties.head._2.pubKeyBytes))

        endorseAttempt match {
          case Some(endorsed) =>
            val allEndorsedAndAgree = Try {
              cc.parties.tail.foldLeft(endorseAttempt.get.asString.get)((a, b) => {
                endorsementsJsonObj(Base58.encode(b._2.pubKeyBytes)) match {
                  case Some(endorsement) =>
                    if(endorsement.asString.get equals a) a
                    else throw new Exception("Contract completion endorsements are not all for the same status")
                  case None =>
                    throw new Exception("Contract completion has not yet been endorsed by all parties")
                }
              })
            }

            val producerProposition = cc.parties(Role.Producer)
            val producerReputationIsValid = Try {
              cc.producerReputation.foreach(claimedBox => {
                closedBox(claimedBox.id) match {
                  case Some(b: ReputationBox) =>
                    if (b.proposition != producerProposition)
                      throw new Exception(s"Claimed reputation box had proposition $producerProposition but actually had ${b.proposition}")

                    if (b.value != claimedBox.value)
                      throw new Exception(s"Claimed reputation box with value ${claimedBox.value} but actually had ${b.value}")

                  case None => throw new Exception("Reputation box not found")
                  case Some(o) => throw new Exception(s"Reputation box expected, found ${o.typeOfBox} instead")
                }

              })
            }

            /* Handles fees */
            val boxesSumMapTry: Try[Map[PublicKey25519Proposition, Long]] = {
              cc.unlockers.tail.foldLeft[Try[Map[PublicKey25519Proposition, Long]]](Success(Map()))((partialRes, unlocker) => {
                partialRes.flatMap(_ => closedBox(unlocker.closedBoxId) match {
                  case Some(box: PolyBox) =>
                    if (unlocker.boxKey.isValid(box.proposition, cc.messageToSign)) {
                      partialRes.get.get(box.proposition) match {
                        case Some(total) => Success(partialRes.get + (box.proposition -> (total + box.value)))
                        case None => Success(partialRes.get + (box.proposition -> box.value))
                      }
                    } else {
                      Failure(new Exception("Incorrect unlocker"))
                    }
                  case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
                })
              })
            }

            /* Incorrect unlocker or box provided, or not enough to cover declared fees */
            val enoughForFees = Try {
              if (boxesSumMapTry.isFailure || !boxesSumMapTry.get.forall { case (prop, amount) => cc.fees.get(prop) match {
                case Some(fee) => amount >= fee
                case None => true
              }}) throw new Exception("Insufficient balances provided for fees")
            }

            allEndorsedAndAgree
              .flatMap(_ => producerReputationIsValid)
              .flatMap(_ => enoughForFees)
              .flatMap(_ => semanticValidity(cc))

          case None => throw new Exception(s"Contract completion has not yet been endorsed by all parties")
        }
      }
    }
  }

  /**
    *
    * @param ar:  the AssetRedemption to validate
    * @return
    */
  //noinspection ScalaStyle
  def validateAssetRedemption(ar: AssetRedemption): Try[Unit] = {
    val statefulValid: Try[Unit] = {

      /* First check that all the proposed boxes exist */
      val availableAssetsTry: Try[Map[String, Long]] = ar.unlockers.foldLeft[Try[Map[String, Long]]](Success(Map[String, Long]()))((partialRes, unlocker) =>

        partialRes.flatMap(partialMap =>
          /* Checks if unlocker is valid and if so adds to current running total */
          closedBox(unlocker.closedBoxId) match {
            case Some(box: AssetBox) =>
              if (unlocker.boxKey.isValid(box.proposition, ar.messageToSign) && (box.hub equals ar.hub)) {
                Success(partialMap.get(box.assetCode) match {
                  case Some(amount) => partialMap + (box.assetCode -> (amount + box.value))
                  case None => partialMap + (box.assetCode -> box.value)
                })
              } else {
                Failure(new Exception("Incorrect unlocker"))
              }
            case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
          }
        )

      )

      /* Make sure that there's enough to cover the remainders */
      val enoughAssets = availableAssetsTry.flatMap(availableAssets =>
        ar.remainderAllocations.foldLeft[Try[Unit]](Success()) { case (partialRes, (assetCode, remainders)) =>
        partialRes.flatMap(_ => availableAssets.get(assetCode) match {
            case Some(amount) => if(amount > remainders.map(_._2).sum) Success() else Failure(new Exception("Not enough assets"))
            case None => Failure(new Exception("Asset not included in inputs"))
          })
        }
      )

      /* Handles fees */
      val boxesSumTry: Try[Long] = {
        ar.unlockers.tail.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) => {
          partialRes.flatMap(total => closedBox(unlocker.closedBoxId) match {
            case Some(box: PolyBox) =>
              if (unlocker.boxKey.isValid(box.proposition, ar.messageToSign)) {
                  Success(total + box.value)
              } else {
                Failure(new Exception("Incorrect unlocker"))
              }
            case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
          })
        })
      }

      /* Incorrect unlocker or box provided, or not enough to cover declared fees */
      val enoughToCoverFees = Try {
        if (boxesSumTry.isFailure || boxesSumTry.get < ar.fee)
          throw new Exception("Insufficient balances provided for fees")
      }

      enoughAssets.flatMap(_ => enoughToCoverFees)
    }

    statefulValid.flatMap(_ => semanticValidity(ar))
  }
}

object BifrostState {

  type T = Any
  type TX = BifrostTransaction
  type P = ProofOfKnowledgeProposition[PrivateKey25519]
  type BX = BifrostBox
  type BPMOD = BifrostBlock
  type GSC = GenericStateChanges[T, P, BX]
  type BSC = BifrostStateChanges

  def semanticValidity(tx: TX): Try[Unit] = {
    tx match {
      case poT: PolyTransfer => PolyTransfer.validate(poT)
      case arT: ArbitTransfer => ArbitTransfer.validate(arT)
      case asT: AssetTransfer => AssetTransfer.validate(asT)
      case cc: ContractCreation => ContractCreation.validate(cc)
      case ccomp: ContractCompletion => ContractCompletion.validate(ccomp)
      case prT: ProfileTransaction => ProfileTransaction.validate(prT)
      case cme: ContractMethodExecution => ContractMethodExecution.validate(cme)
      case ar: AssetRedemption => AssetRedemption.validate(ar)
      case _ => throw new Exception("Semantic validity not implemented for " + tx.getClass.toGenericString)
    }
  }


  def changes(mod: BPMOD): Try[GSC] = {
    Try {
      val initial = (Set(): Set[Array[Byte]], Set(): Set[BX], 0L)

      val gen = mod.forgerBox.proposition

      val boxDeltas: Seq[(Set[Array[Byte]], Set[BX], Long)] = mod.transactions match {
        case Some(txSeq) => txSeq.map(tx => (tx.boxIdsToOpen.toSet, tx.newBoxes.toSet, tx.fee))
      }

      val (toRemove: Set[Array[Byte]], toAdd: Set[BX], reward: Long) =
        boxDeltas.foldLeft((Set[Array[Byte]](), Set[BX](), 0L))((aggregate, boxDelta) => {
          (aggregate._1 ++ boxDelta._1, aggregate._2 ++ boxDelta._2, aggregate._3 + boxDelta._3 )
        })

      val rewardNonce = Longs.fromByteArray(mod.id.take(Longs.BYTES))

      var finalToAdd = toAdd
      if (reward != 0) finalToAdd += PolyBox(gen, rewardNonce, reward)

      //no reward additional to tx fees
      BifrostStateChanges(toRemove, finalToAdd, mod.timestamp)
    }
  }

  def readOrGenerate(settings: Settings, callFromGenesis: Boolean = false): BifrostState = {
    val dataDirOpt = settings.dataDirOpt.ensuring(_.isDefined, "data dir must be specified")
    val dataDir = dataDirOpt.get

    new File(dataDir).mkdirs()

    val iFile = new File(s"$dataDir/state")
    iFile.mkdirs()
    val stateStorage = new LSMStore(iFile)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        stateStorage.close()
      }
    })
    val version = stateStorage.lastVersionID.fold(Array.emptyByteArray)(_.data)

    var timestamp: Long = 0L
    if (callFromGenesis) {
      timestamp = System.currentTimeMillis()
    } else {
      timestamp = Longs.fromByteArray(stateStorage.get(ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes))).get.data)
    }

    BifrostState(stateStorage, version, timestamp)
  }

  def genesisState(settings: Settings, initialBlocks: Seq[BPMOD]): BifrostState = {
    initialBlocks.foldLeft(readOrGenerate(settings, callFromGenesis = true)) { (state, mod) =>
      state.changes(mod).flatMap(cs => state.applyChanges(cs, mod.id)).get
    }
  }
}