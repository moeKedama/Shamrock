package moe.fuqiuluo.http.action.helper.msg

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Base64
import com.tencent.mobileqq.emoticon.QQSysFaceUtil
import com.tencent.qqnt.kernel.nativeinterface.FaceElement
import com.tencent.qqnt.kernel.nativeinterface.MsgConstant
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.kernel.nativeinterface.PttElement
import com.tencent.qqnt.kernel.nativeinterface.QQNTWrapperUtil
import com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import moe.fuqiuluo.http.action.helper.FileHelper
import moe.fuqiuluo.http.action.helper.HighwayHelper
import moe.fuqiuluo.http.action.helper.MediaType
import moe.fuqiuluo.http.action.helper.codec.AudioUtils
import moe.fuqiuluo.xposed.helper.ServiceFetcher
import moe.fuqiuluo.xposed.helper.msgService
import moe.fuqiuluo.xposed.tools.GlobalClient
import moe.fuqiuluo.xposed.tools.asBooleanOrNull
import moe.fuqiuluo.xposed.tools.asInt
import moe.fuqiuluo.xposed.tools.asString
import moe.fuqiuluo.xposed.tools.asStringOrNull
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

internal typealias IMaker = suspend (Int, String, JsonObject) -> MsgElement

internal object MessageMaker {
    private val makerArray = mutableMapOf(
        "text" to ::createTextElem,
        "face" to ::createFaceElem,
        "pic" to ::createImageElem,
        "image" to ::createImageElem,
        "record" to ::createRecordElem,
    )

    private suspend fun createRecordElem(chatType: Int, target: String, data: JsonObject): MsgElement {
        val url = data["file"].asString
        var file = if (url.startsWith("base64://")) {
            FileHelper.saveFileToCache(ByteArrayInputStream(
                Base64.decode(url.substring(9), Base64.DEFAULT)
            ))
        } else if (url.startsWith("file:///")) {
            File(url.substring(8))
        } else {
            kotlin.run {
                val respond = GlobalClient.get(url)
                if (respond.status != HttpStatusCode.OK) {
                    throw Exception("download image failed: ${respond.status}")
                }
                FileHelper.saveFileToCache(respond.bodyAsChannel())
            }
        }
        val isMagic = data["magic"].asBooleanOrNull ?: false

        val ptt = PttElement()

        when (FileHelper.getMediaType(file)) {
            MediaType.Silk -> {
                ptt.formatType = MsgConstant.KPTTFORMATTYPESILK
                // NOTHING TO DO
            }
            MediaType.Amr -> {
                ptt.duration = (FileHelper.getAudioDuration(file.absolutePath) * 0.001f).roundToInt()
                ptt.formatType = MsgConstant.KPTTFORMATTYPEAMR
            }
            else -> {
                file = AudioUtils.audioToPcm(file)
                AudioUtils.pcmToSilk(file).let {
                    file.delete()
                    file = it.first
                    ptt.duration = (it.second).roundToInt()
                }
                ptt.formatType = MsgConstant.KPTTFORMATTYPESILK
            }
        }

        if (chatType == MsgConstant.KCHATTYPEGROUP) {
            HighwayHelper.transTroopVoice(target, file)
        }

        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEPTT
        ptt.md5HexStr = QQNTWrapperUtil.CppProxy.genFileMd5Hex(file.absolutePath)

        val msgService = ServiceFetcher.kernelService.msgService!!
        val originalPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
            4, 0, ptt.md5HexStr, file.name, 1, 0, null, "", true
        ))!!
        if (!QQNTWrapperUtil.CppProxy.fileIsExist(originalPath) || QQNTWrapperUtil.CppProxy.getFileSize(originalPath) != file.length()) {
            QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, originalPath)
        }

        ptt.fileName = originalPath.substring(originalPath.lastIndexOf("/") + 1)
        ptt.filePath = originalPath
        ptt.fileSize = QQNTWrapperUtil.CppProxy.getFileSize(originalPath)

        if (!isMagic) {
            ptt.voiceType = MsgConstant.KPTTVOICETYPESOUNDRECORD
            ptt.voiceChangeType = MsgConstant.KPTTVOICECHANGETYPENONE
        } else {
            ptt.voiceType = MsgConstant.KPTTVOICETYPEVOICECHANGE
            ptt.voiceChangeType = MsgConstant.KPTTVOICECHANGETYPEECHO
        }

        ptt.canConvert2Text = false
        ptt.fileId = 0
        ptt.fileUuid = ""
        ptt.text = ""

        elem.pttElement = ptt
        return elem
    }

    private suspend fun createImageElem(chatType: Int, target: String, data: JsonObject): MsgElement {
        val isOriginal = data["original"].asBooleanOrNull ?: true
        val isFlash = data["flash"].asBooleanOrNull ?: false
        val url = data["file"].asString
        val file = if (url.startsWith("base64://")) {
            FileHelper.saveFileToCache(ByteArrayInputStream(
                Base64.decode(url.substring(9), Base64.DEFAULT)
            ))
        } else if (url.startsWith("file:///")) {
            File(url.substring(8))
        } else {
            kotlin.run {
                val respond = GlobalClient.get(url)
                if (respond.status != HttpStatusCode.OK) {
                    throw Exception("download image failed: ${respond.status}")
                }
                FileHelper.saveFileToCache(respond.bodyAsChannel())
            }
        }

        if (chatType == MsgConstant.KCHATTYPEGROUP) {
            HighwayHelper.transTroopPic(target, file)
        }

        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEPIC
        val pic = PicElement()
        pic.md5HexStr = QQNTWrapperUtil.CppProxy.genFileMd5Hex(file.absolutePath)

        val msgService = ServiceFetcher.kernelService.msgService!!
        val originalPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
            2, 0, pic.md5HexStr, file.name, 1, 0, null, "", true
        ))
        if (!QQNTWrapperUtil.CppProxy.fileIsExist(originalPath) || QQNTWrapperUtil.CppProxy.getFileSize(originalPath) != file.length()) {
            val thumbPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
                2, 0, pic.md5HexStr, file.name, 2, 720, null, "", true
            ))
            QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, originalPath)
            QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, thumbPath)
        }

        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
        val exifInterface = ExifInterface(file.absolutePath)
        val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        if (orientation != ExifInterface.ORIENTATION_ROTATE_90 && orientation != ExifInterface.ORIENTATION_ROTATE_270) {
            pic.picWidth = options.outWidth
            pic.picHeight = options.outHeight
        } else {
            pic.picWidth = options.outHeight
            pic.picHeight = options.outWidth
        }
        pic.sourcePath = file.absolutePath
        pic.fileSize = QQNTWrapperUtil.CppProxy.getFileSize(file.absolutePath)
        pic.original = isOriginal
        pic.picType = FileHelper.getPicType(file)
        pic.isFlashPic = isFlash

        elem.picElement = pic
        return elem
    }

    private suspend fun createFaceElem(chatType: Int, target: String, data: JsonObject): MsgElement {
        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEFACE
        val face = FaceElement()

        // 4 is market face
        face.faceType = 0

        val serverId =  data["id"].asInt
        val localId = QQSysFaceUtil.convertToLocal(serverId)

        face.faceIndex = serverId

        face.faceText = QQSysFaceUtil.getFaceDescription(localId)

        face.imageType = 0
        face.packId = "0"
        elem.faceElement = face

        return elem
    }

    private suspend fun createTextElem(chatType: Int, target: String, data: JsonObject): MsgElement {
        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPETEXT
        val text = TextElement()
        text.content = data["text"].asStringOrNull ?: "null"
        elem.textElement = text
        return elem
    }

    operator fun get(type: String): IMaker? = makerArray[type]
}