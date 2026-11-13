package com.example.grouppurchase.purchase.interfaces;

import com.example.grouppurchase.purchase.application.FindGroupPurchases.Page;
import com.example.grouppurchase.purchase.domain.GroupPurchases.View;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PurchaseResponses {
    private PurchaseResponses() { }

    static Listing listing(Page page, boolean owned) {
        return new Listing(page.items().stream().map(view -> fields(view, owned, false)).toList(), page.nextCursor());
    }

    static Map<String, Object> detail(View view, boolean owned) {
        return fields(view, owned, true);
    }

    private static Map<String, Object> fields(View view, boolean owned, boolean detailed) {
        var purchase = view.purchase();
        var terms = purchase.terms();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", purchase.id());
        result.put("status", purchase.status());
        result.put("productName", terms.productName());
        if (detailed) result.put("productDescription", terms.productDescription());
        result.put("unitPrice", terms.unitPrice());
        result.put("baseQuantity", terms.baseQuantity());
        result.put("finalDeadline", terms.finalDeadline());
        result.put("initialRecruitmentTarget", purchase.initialRecruitmentTarget());
        result.put("publishedAt", purchase.publishedAt());
        result.put("recruitmentOpen", view.recruitmentOpen());
        if (owned) {
            result.put("createdAt", purchase.createdAt());
            result.put("updatedAt", purchase.updatedAt());
            if (detailed) {
                result.put("draftVersion", purchase.draftVersion());
                result.put("projectedInitialRecruitmentTarget", purchase.projectedInitialRecruitmentTarget());
            }
        }
        return result;
    }

    record Listing(List<Map<String, Object>> items, String nextCursor) { }
}
