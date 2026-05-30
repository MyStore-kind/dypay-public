/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jeequan.jeepay.pay.rqrs.payorder;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import com.jeequan.jeepay.core.constants.CS;
import com.jeequan.jeepay.pay.rqrs.AbstractMchAppRQ;
import com.jeequan.jeepay.pay.rqrs.payorder.payway.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.validator.constraints.Range;
import org.springframework.beans.BeanUtils;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/*
* 创建订单请求参数对象
* 聚合支付接口（统一下单）
*
* @author terrfly
* @site https://www.jeequan.com
* @date 2021/6/8 17:33
*/
@Data
@Schema(description = "统一下单请求参数（含国际化反风控扩展字段）")
public class UnifiedOrderRQ extends AbstractMchAppRQ {

    /** 商户订单号 **/
    @NotBlank(message="商户订单号不能为空")
    @Schema(description = "商户订单号（商户系统内唯一）", example = "M20260530000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String mchOrderNo;

    /** 支付方式  如： wxpay_jsapi,alipay_wap等   **/
    @NotBlank(message="支付方式不能为空")
    @Schema(description = "支付方式编码，如 stripe_pc / pp_pc / ali_wap / wx_jsapi", example = "stripe_pc", requiredMode = Schema.RequiredMode.REQUIRED)
    private String wayCode;

    /** 支付金额， 单位：分 **/
    @NotNull(message="支付金额不能为空")
    @Min(value = 1, message = "支付金额不能为空")
    @Schema(description = "支付金额（最小币种单位，如美分/日元整数）", example = "1999", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long amount;

    /** 货币代码 **/
    @NotBlank(message="货币代码不能为空")
    @Schema(description = "ISO 4217 三位货币代码（大写）", example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String currency;

    /** 客户端IP地址 **/
    @Schema(description = "客户端真实 IP（用于风控 IP 国家识别）", example = "203.0.113.42")
    private String clientIp;

    /** 商品标题 **/
    @NotBlank(message="商品标题不能为空")
    @Schema(description = "商品标题（向用户展示）", example = "Premium Plan - Monthly", requiredMode = Schema.RequiredMode.REQUIRED)
    private String subject;

    /** 商品描述信息 **/
    @NotBlank(message="商品描述信息不能为空")
    @Schema(description = "商品描述（拒付证据用）", example = "Order #M20260530000001 premium membership", requiredMode = Schema.RequiredMode.REQUIRED)
    private String body;

    /** 异步通知地址 **/
    @Schema(description = "商户异步回调地址（仅 http/https）", example = "https://merchant.example.com/notify")
    private String notifyUrl;

    /** 跳转通知地址 **/
    @Schema(description = "支付完成跳转地址", example = "https://merchant.example.com/return")
    private String returnUrl;

    /** 订单失效时间, 单位：秒 **/
    @Schema(description = "订单失效时间（秒）", example = "3600")
    private Integer expiredTime;

    /** 特定渠道发起额外参数 **/
    @Schema(description = "通道专属扩展参数 JSON 字符串")
    private String channelExtra;

    /** 商户扩展参数 **/
    @Schema(description = "商户扩展参数（原样回传）")
    private String extParam;

    /** 分账模式： 0-该笔订单不允许分账, 1-支付成功按配置自动完成分账, 2-商户手动分账(解冻商户金额) **/
    @Range(min = 0, max = 2, message = "分账模式设置值有误")
    @Schema(description = "分账模式：0 不分账 / 1 自动 / 2 手动（含冻结）", example = "0", allowableValues = {"0","1","2"})
    private Byte divisionMode;

    // ===== 国际四方扩展：买家与设备信息（用于风控评分） =====
    // 这些字段全部可选，缺失时风控按 PASS 处理

    /** 买家邮箱（用于黑名单 / 一次性邮箱识别） */
    @Schema(description = "买家邮箱（风控：黑名单/一次性邮箱识别）", example = "buyer@example.com")
    private String buyerEmail;

    /** 买家手机 */
    @Schema(description = "买家手机（E.164 格式）", example = "+14155552671")
    private String buyerPhone;

    /** 买家姓名（拒付证据） */
    @Schema(description = "买家姓名（拒付证据/AVS）", example = "John Doe")
    private String buyerName;

    /** 客户端国家代码 ISO 3166-1 alpha-2（如 US/JP） */
    @Schema(description = "客户端国家代码 ISO 3166-1 alpha-2", example = "US")
    private String ipCountry;

    /** 客户端 IP 风险等级（low/mid/high，可对接第三方风险库） */
    @Schema(description = "IP 风险等级", example = "low", allowableValues = {"low","mid","high"})
    private String ipRiskLevel;

    /** 设备指纹（前端 FingerprintJS 等生成） */
    @Schema(description = "设备指纹（FingerprintJS 等生成）", example = "fp_8f3c2e1a9b4d")
    private String deviceFingerprint;

    /** User Agent */
    @Schema(description = "浏览器 User Agent")
    private String userAgent;

    /** 卡 BIN（前 6/8 位）—— 用于卡级别风控 */
    @Schema(description = "卡 BIN（前 6 或 8 位）", example = "424242")
    private String cardBin;

    /** 卡尾号 4 位 */
    @Schema(description = "卡尾号 4 位", example = "4242")
    private String cardLast4;

    /** 卡发行国家 */
    @Schema(description = "卡发行国家 ISO 3166-1 alpha-2", example = "US")
    private String cardCountry;

    /** 卡类型 credit/debit/prepaid */
    @Schema(description = "卡类型", example = "credit", allowableValues = {"credit","debit","prepaid"})
    private String cardType;

    /** 卡品牌 visa/mastercard/amex */
    @Schema(description = "卡品牌", example = "visa", allowableValues = {"visa","mastercard","amex","jcb","discover","unionpay"})
    private String cardBrand;

    /** 返回真实的bizRQ **/
    public UnifiedOrderRQ buildBizRQ(){

        if(CS.PAY_WAY_CODE.ALI_BAR.equals(wayCode)){
            AliBarOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliBarOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.ALI_JSAPI.equals(wayCode)){
            AliJsapiOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliJsapiOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.ALI_LITE.equals(wayCode)){
            AliLiteOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliLiteOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.QR_CASHIER.equals(wayCode)){
            QrCashierOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), QrCashierOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.WX_JSAPI.equals(wayCode)){
            WxJsapiOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), WxJsapiOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.WX_LITE.equals(wayCode)){
            WxLiteOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), WxLiteOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.WX_BAR.equals(wayCode)){
            WxBarOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), WxBarOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.WX_NATIVE.equals(wayCode)){
            WxNativeOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), WxNativeOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.WX_H5.equals(wayCode)){
            WxH5OrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), WxH5OrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.YSF_BAR.equals(wayCode)){
            YsfBarOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), YsfBarOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.YSF_JSAPI.equals(wayCode)){
            YsfJsapiOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), YsfJsapiOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.AUTO_BAR.equals(wayCode)){
            AutoBarOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AutoBarOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.ALI_APP.equals(wayCode)){
            AliAppOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliAppOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.ALI_WAP.equals(wayCode)){
            AliWapOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliWapOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.ALI_PC.equals(wayCode)){
            AliPcOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliPcOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if(CS.PAY_WAY_CODE.ALI_QR.equals(wayCode)){
            AliQrOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliQrOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }else if (CS.PAY_WAY_CODE.PP_PC.equals(wayCode)){
            PPPcOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), PPPcOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        } else if (CS.PAY_WAY_CODE.ALI_OC.equals((wayCode))) {
            AliOcOrderRQ bizRQ = JSONObject.parseObject(StringUtils.defaultIfEmpty(this.channelExtra, "{}"), AliOcOrderRQ.class);
            BeanUtils.copyProperties(this, bizRQ);
            return bizRQ;
        }

        return this;
    }

    /** 获取渠道用户ID **/
    @JSONField(serialize = false)
    public String getChannelUserId(){
        return null;
    }

}
