/******************************************************************************
 * Copyright © 2013-2016 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package prizm.http;

import prizm.Account;
import prizm.Attachment;
import prizm.Constants;
import prizm.DigitalGoodsStore;
import prizm.PrizmException;
import prizm.crypto.EncryptedData;
import prizm.util.Convert;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

import static prizm.http.JSONResponses.ALREADY_DELIVERED;
import static prizm.http.JSONResponses.INCORRECT_DGS_DISCOUNT;
import static prizm.http.JSONResponses.INCORRECT_DGS_GOODS;
import static prizm.http.JSONResponses.INCORRECT_PURCHASE;

public final class DGSDelivery extends CreateTransaction {

    static final DGSDelivery instance = new DGSDelivery();

    private DGSDelivery() {
        super(new APITag[] {APITag.DGS, APITag.CREATE_TRANSACTION},
                "purchase", "discountNQT", "goodsToEncrypt", "goodsIsText", "goodsData", "goodsNonce");
    }

    @Override
    protected JSONStreamAware processRequest(HttpServletRequest req) throws PrizmException {

        Account sellerAccount = ParameterParser.getSenderAccount(req);
        DigitalGoodsStore.Purchase purchase = ParameterParser.getPurchase(req);
        if (sellerAccount.getId() != purchase.getSellerId()) {
            return INCORRECT_PURCHASE;
        }
        if (! purchase.isPending()) {
            return ALREADY_DELIVERED;
        }

        String discountValueNQT = Convert.emptyToNull(req.getParameter("discountNQT"));
        long discountNQT = 0;
        try {
            if (discountValueNQT != null) {
                discountNQT = Long.parseLong(discountValueNQT);
            }
        } catch (RuntimeException e) {
            return INCORRECT_DGS_DISCOUNT;
        }
        if (discountNQT < 0
                || discountNQT > Constants.MAX_BALANCE_NQT
                || discountNQT > Math.multiplyExact(purchase.getPriceNQT(), (long) purchase.getQuantity())) {
            return INCORRECT_DGS_DISCOUNT;
        }

        Account buyerAccount = Account.getAccount(purchase.getBuyerId());
        boolean goodsIsText = !"false".equalsIgnoreCase(req.getParameter("goodsIsText"));
        EncryptedData encryptedGoods = ParameterParser.getEncryptedData(req, "goods");
        byte[] goodsBytes = null;
        boolean broadcast = !"false".equalsIgnoreCase(req.getParameter("broadcast"));

        if (encryptedGoods == null) {
            try {
                String plainGoods = Convert.nullToEmpty(req.getParameter("goodsToEncrypt"));
                if (plainGoods.length() == 0) {
                    return INCORRECT_DGS_GOODS;
                }
                goodsBytes = goodsIsText ? Convert.toBytes(plainGoods) : Convert.parseHexString(plainGoods);
            } catch (RuntimeException e) {
                return INCORRECT_DGS_GOODS;
            }
            String secretPhrase = ParameterParser.getSecretPhrase(req, broadcast);
            if (secretPhrase != null) {
                encryptedGoods = buyerAccount.encryptTo(goodsBytes, secretPhrase, true);
            }
        }

        Attachment attachment = encryptedGoods == null ?
                new Attachment.UnencryptedDigitalGoodsDelivery(purchase.getId(), goodsBytes,
                        goodsIsText, discountNQT, Account.getPublicKey(buyerAccount.getId())) :
                new Attachment.DigitalGoodsDelivery(purchase.getId(), encryptedGoods,
                        goodsIsText, discountNQT);
        return createTransaction(req, sellerAccount, buyerAccount.getId(), 0, attachment);

    }

}
