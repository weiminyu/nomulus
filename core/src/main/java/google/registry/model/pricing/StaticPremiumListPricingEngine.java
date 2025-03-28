// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.pricing;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import com.google.common.net.InternetDomainName;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumListDao;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** A premium list pricing engine that stores static pricing information in database entities. */
public final class StaticPremiumListPricingEngine implements PremiumPricingEngine {

  /** The name of the pricing engine, as used in {@code Registry.pricingEngineClassName}. */
  public static final String NAME = "google.registry.model.pricing.StaticPremiumListPricingEngine";

  @Inject StaticPremiumListPricingEngine() {}

  @Override
  public DomainPrices getDomainPrices(String domainName, DateTime priceTime) {
    String tldStr = getTldFromDomainName(domainName);
    String label = InternetDomainName.from(domainName).parts().get(0);
    Tld tld = Tld.get(checkNotNull(tldStr, "tld"));
    Optional<Money> premiumPrice =
        tld.getPremiumListName().flatMap(pl -> PremiumListDao.getPremiumPrice(pl, label));
    return DomainPrices.create(
        premiumPrice.isPresent(),
        premiumPrice.orElse(tld.getCreateBillingCost(priceTime)),
        premiumPrice.orElse(tld.getStandardRenewCost(priceTime)));
  }
}
