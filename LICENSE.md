# ⚖️ Defensive Public Source (Non-Commercial) License v1.0.1
This is a quick guide to understand the permits and forbids of the license.
> [!WARNING]
> This section is merely informative and doesn't guarantee any interpretation to any clause, 
full clauses are below this section

| ✅ You can                             | ❌ You can't                                     | ℹ️ You must                                         |
|----------------------------------------|--------------------------------------------------|----------------------------------------------------|
| Use it in free projects                | Sell anything that uses it                       | Include the license and credit J-RAP (SrRapero720) |
| Make mods and apps with it             | Put it inside your mod's jar                     | Keep forks free and open source on GitHub          |
| Fork and modify it                     | Upload it outside CurseForge, Modrinth or GitHub | Give J-RAP 50% of your fork's rewards              |
| Port it to versions it doesn't support | Port it to versions it already supports          | Let WATERMeDIA use what you publish in your fork   |
| Keep donations and your mod's rewards  | Make it run where it blocks itself on purpose    | Fix any problem within 32 days of being warned     |

## 💰 Money
- **Donations**: All yours, as long as they don't unlock anything.
- **Rewards and Revenue**: 50% goes to J-RAP when they come from WATERMeDIA or your fork [they come from WATERMeDIA or your fork (§3.3)](LICENSE.md#33-reward-programs). When it comes from another mod that uses WATERMeDIA, they're all yours.
- **Sales**: You can't sell anything that uses watermedia, [no exception (§2)](LICENSE.md#2-copyright-license). Unless I (J-RAP) grant you a [commercial license (§2.2)](LICENSE.md#22-commercial-licensing).

## 🎮 Frequent concerns
- **Port WATERMeDIA to 1.12.2**: ✅ you can. WATERMeDIA doesn't run there, so your port doesn't compete with it
  ([§4.4](LICENSE.md#44-unsupported-environments)).
- **Port WATERMeDIA to 1.21.11**: ❌ you can't. [§4.3](LICENSE.md#43-non-competition) stops you because WATERMeDIA
  already runs there and your port would compete with it. Send a pull request instead.
- **Stop WATERMeDIA from using something it copied from my fork**: ❌ you can't.
  [§4.2](LICENSE.md#42-license-back) lets WATERMeDIA use anything you publish in your fork, for free. Your code is
  still yours.
- **Put WATERMeDIA inside my mod's jar**: ❌ you can't. [§2.1](LICENSE.md#21-dependant-use) requires players to
  download it separately. Standalone apps can include it.
- **Upload WATERMeDIA or my fork to another site or launcher**: ❌ you can't.
  [§3](LICENSE.md#3-distribution-license) only allows CurseForge, Modrinth and GitHub.
- **Make a free modpack with WATERMeDIA**: ✅ you can, as long as it downloads WATERMeDIA from CurseForge or Modrinth.
- **Keep my mod closed source**: ✅ you can, if it only uses WATERMeDIA. Forks must be open source on GitHub
  ([§4.1](LICENSE.md#41-source-availability)).
- **Publish a fork**: ✅ you can, if it doesn't compete with WATERMeDIA (see the ports above). Say it's based on
  WATERMeDIA and include the license and the line
  `Copyright SrRapero720 (https://github.com/SrRapero720)` ([§3.4](LICENSE.md#34-notices)).
- **Fork someone else's fork**: ✅ you can, with the same rules as any fork. [§4.5](LICENSE.md#45-same-terms) keeps
  every fork under this license, so its author can't block you or add rules, and neither can you. Keep their credit;
  the 50% of rewards still goes only to J-RAP.

_end of informative section_

---
# Defensive Public Source (Non-Commercial) License v1.0.1
DPS (NC) License - https://github.com/SrRapero720/licenses/blob/main/DPS-(NC)-License.md

## Platforms
- ``curseforge.com``
- ``modrinth.com``

## Required Notice
``Copyright SrRapero720 (https://github.com/SrRapero720)``

## 0. Definitions
- **Licensor**: The individual or legal entity named in the [Required Notice](#required-notice).

- **Software**: The software, including its source code, executable code, data, and accompanying
  documentation, that the Licensor makes available under these terms.

- **You**: The individual or entity agreeing to these terms.

- **Your Licenses**: All the licenses that the Licensor granted to You for the Software under these terms.

- **Environment**: The hardware architecture, operating system, host application, loader, runtime,
  and dependencies in which software runs, including their applicable versions.

- **Supported Environment**: An Environment in which the latest stable release of the Software can
  perform the relevant functions under the native compatibility criteria in the definition of Direct Competition.

- **Unsupported Environment**: An Environment that is neither a Supported Environment nor a Restricted Environment.

- **Restricted Environment**: An Environment that the Software explicitly refuses through a Software Restriction.
  Omitting an Environment from a manifest or list of supported versions is not, by itself, an explicit refusal.

- **Product**: A good, a service, or a combination of both.

- **Derivative Work**: Modifications of the original Software performed by third-party entities.

- **Commercial Purpose**: A purpose intended to obtain Symmetric Income.
  Receiving Asymmetric Income does not, by itself, constitute a Commercial
  Purpose or exempt any Commercial Purpose from the restrictions of these terms.

- **Noncommercial Purpose**: Any purpose that is not a Commercial Purpose.

- **Symmetric Income**: Income received in exchange for the Software,
  a Derivative Work, or a Dependant, including payments for access,
  functionality, or services provided through any of them, whether offered
  separately or as part of another Product. Income remains Symmetric Income
  regardless of whether it produces a profit or is used to cover costs,
  development, or maintenance.

- **Asymmetric Income**: Income from a Reward Program or voluntarily contributed to support the Software,
  a Derivative Work, or a Dependant, without being given in exchange for access,
  functionality, services, or a financial return related to any of them.
  This includes donations and other contributions that meet these conditions.

- **Reward Program**: An asymmetric rewards program offered by a Platform, funded by advertising
  as indirect sponsorship, referrals, or other rewards that are not consideration for access to,
  functionality of, or services provided through the Software, a Derivative Work, or a Dependant.
  Compensation for advertising exposure or referrals qualifies if access to and functionality of
  the Software, Derivative Work, or Dependant are not conditional on a payment or purchase.
  Rewards meeting this definition are Asymmetric Income.

- **Platform(s)**: Online distributors listed under [Platforms](#platforms)
  where the Software can be published to End Users.

- **Distribution**: The act of making the Software or a Derivative Work publicly available.

- **End User**: An individual or entity that receives and uses any version of the Software
  or a Derivative Work, including a prerelease version.

- **Dependant**: Third-party software or a project that depends on or makes functional references
  to the current Software.

- **Direct Competition**: A Product provided using the Software, including a Derivative Work or a Dependant,
  is in Direct Competition with the Software or another Product the Licensor provides using the Software
  if and only if both of the following conditions are met:

  1. The Product is offered or otherwise made available to third parties and is reasonably capable
     of replacing the Software or that other Product of the Licensor for substantially the same
     use cases and substantially the same class of users; and
  2. The latest stable release available from the Licensor of the Software or other Product being
     replaced is natively compatible with the Environment in which Your Product is offered for use
     and can perform the functions relevant to that substitution without modification, except for
     the manifest changes described below.

  Native compatibility depends on actual usability in the relevant Environment, regardless of official
  support, testing, or compatibility guarantees from the Licensor. If the only changes needed are to
  version declarations in a manifest or equivalent compatibility metadata, the release is considered
  natively compatible, provided it performs the relevant functions without any other changes.
  A Derivative Work that only makes those manifest changes is therefore in Direct Competition
  when it meets the first condition. This exception does not authorize bypassing a Software Restriction.
  Compatibility achieved only through third-party emulation does not constitute native compatibility.

  Both conditions are assessed continuously for as long as the Product is offered or otherwise made
  available to third parties, using the latest stable release available from the Licensor at each time.
  A Product becomes subject to section 4.3 as soon as both conditions are met, including when a new
  stable release makes the relevant substitution possible. Earlier publication of the Product does
  not preserve a previous finding that it was not in Direct Competition.

  A Product may satisfy these conditions even if it is provided without charge, has different
  functionality or interfaces, or includes additional functionality.

- **Software Restriction**: A deliberate restriction implemented by the Licensor that causes the Software
  to terminate execution upon detecting a restricted hardware or software environment,
  with an explicit indication that the restriction is the reason for termination. An unmet technical
  requirement or a failure to recognize the environment does not, by itself, constitute a Software Restriction.

## 1. Acceptance
In order to get any license under these terms, You must agree to them as both strict
obligations and conditions to all Your licenses.

### 1.1 Amend Terms
The Licensor may publish revised versions of these terms at any time, and may make new
releases of the Software available under different terms. Your licenses to use copies of
the Software You already have continue under the terms those copies were made available
under. However, Distribution of the Software or of Derivative Works that continues beyond 32
days after the Licensor publishes revised terms must comply with the revised terms.

Revised terms considered for this section are published by the Licensor on the Software's Platforms.

### 1.2 Term
Your licenses last for the entire duration of the rights they cover, unless they end under section 7.
Every license You grant under these terms lasts for the entire duration of the rights it covers and
survives the end of Your licenses. These terms are an express agreement on duration and prevail over
any shorter term that applicable law provides by default.

## 2. Copyright License
Subject to all conditions and restrictions of these terms, the Licensor grants You a
non-exclusive, worldwide, non-transferable, royalty-free copyright license to do everything
You might do with the Software that would otherwise
infringe the Licensor's copyright in it for any Noncommercial Purpose. Distribution is permitted
only as provided in sections 2.1 and 3, and changes and Derivative Works only as provided in section 4.
Commercial Purposes are not licensed under these terms.

### 2.1 Dependant Use
Using the Software as a library, API, or engine inside a Dependant Product is use for a
permitted purpose, provided the Dependant Product itself is provided for a Noncommercial
Purpose.

Dependant standalone desktop applications may embed and redistribute unmodified copies of the Software as
an integrated component of their Dependant Product, through the channel where the
Dependant Product is distributed.

Dependant mods, plugins, and other extensions to third-party software must not embed, bundle,
or redistribute the Software. They must require the Software as an external dependency,
obtained by the End User from a Distribution made under these terms.

### 2.2 Commercial Licensing
Licenses for Commercial Purposes are available separately from the Licensor. Nothing in
these terms prevents the Licensor from granting any other license to anyone else.

## 3. Distribution License
The Licensor grants You an additional copyright license to distribute copies of the
Software or Derivative Works on the Platforms, subject to all conditions and restrictions
of these terms. Except for the integrated Distribution authorized by section 2.1 and the
GitHub Distribution authorized below, You must not distribute them through any other
website, storefront, launcher, file host, or distribution channel.

Distribution of source code and builds of the Software or a Derivative Work on `github.com`
is also authorized, subject to all other conditions and restrictions of these terms.

### 3.1 Platforms
Except as expressly authorized in sections 2.1 and 3, Distribution is licensed only on
the Platforms listed under [Platforms](#platforms).

### 3.2 Platform Terms
You must comply with the terms of service of the Platform on which a Distribution takes place
and, when applicable, the terms of its Reward Program. Distribution or participation in a
Reward Program that violates the applicable terms of service is not licensed.

### 3.3 Reward Programs
Rewards attributable to Distribution of the Software or a Derivative Work through a Reward
Program must be shared equally: 50% (fifty percent) to You and 50% (fifty percent) to the Licensor.
This obligation does not apply to rewards a Dependant earns for its own Dependant Product.

The division is based on the **GROSS REWARD AMOUNT**: the total reward attributed by the Reward
Program to the relevant Distribution before allocation among participants and before withdrawal,
transaction, or currency-conversion charges. Points and other reward units are valued using
the Reward Program's official monetary conversion rate when they become available for redemption.

All allocations, redemptions, and payments must comply with section 3.2 and the terms of service
of the Platform and its Reward Program. Where those terms permit and the program provides direct
allocation among participants, You must configure it to allocate 50% of the GROSS REWARD AMOUNT
to the Licensor. This allocation applies regardless of the amount earned.

Any share that cannot be allocated directly under those terms must be paid by You in money.
That share accrues in the Reward Program's payout currency. Each time the Licensor's total
unpaid monetary share reaches the equivalent of USD 100 (one hundred United States dollars),
You must remit the entire accrued monetary share in the Licensor's local currency. For example,
a GROSS REWARD AMOUNT equivalent to USD 200 creates a Licensor share equivalent to USD 100.
Balances below the threshold carry forward and remain owed.

Only rewards available for withdrawal or redemption under the Reward Program's terms count
toward the payment threshold and the amount then payable. Pending rewards count when they become
available. Once the threshold is reached, You must initiate any necessary cash redemption and
remit the payment upon receiving the proceeds. Currency equivalents are determined using the payment
provider's applicable exchange rates when assessing the threshold and making the payment.
You must not postpone redemption or split withdrawals to avoid the payment threshold.

You must cover all withdrawal, transaction, and currency-conversion charges attributable to
the Licensor's share, including conversion to the Licensor's local currency, in addition to
that share. These charges must not reduce the amount received by the Licensor. If a Platform
deducts such charges directly from the Licensor's allocation, You must reimburse the Licensor.

### 3.4 Notices
You must include a copy of these terms or their URL with every copy of any part of the Software
or a Derivative Work that You distribute.

All copies of the Software and any publicly displayed content related to its distribution, project pages, Platform
listings, and documentation included, must include a minimal copyright notice as shown at the header
of this license in [Required Notice](#required-notice), clearly attributing the Licensor.

## 4. Changes and Derivative Works License
The Licensor grants You an additional copyright license to make changes and Derivative Works
based on the Software for any permitted purpose, subject to the clauses of this section.

### 4.1 Source Availability
While You distribute a Derivative Work, You must keep its complete source code publicly
available on `github.com`, without access restrictions and at no charge, and clearly
state that it is based on the Software and licensed under these terms.

### 4.2 License-Back
As a condition of and in consideration for the licenses granted under these terms, You
grant the Licensor a non-exclusive, worldwide, royalty-free copyright license to use,
reproduce, distribute, relicense, and make new works based on any changes or Derivative
Works You distribute, for any purpose, including commercial licensing of the Software.

Before distributing changes or a Derivative Work that includes contributions owned by other
rightsholders, You must obtain all authorizations necessary to grant the rights required by
this section for those contributions, including commercial relicensing. You must not distribute
contributions for which You have not obtained those authorizations.

### 4.3 Non-Competition
You must not use the Software to offer or otherwise make available a Product in Direct Competition
with the Software or another Product the Licensor provides using the Software, as determined
solely by the definition of Direct Competition.

### 4.4 Unsupported Environments
Providing a Derivative Work for an Unsupported Environment is not, by itself, Direct Competition.
The definition of Direct Competition determines whether it competes with another Product of the
Licensor. An Environment previously supported and later dropped is assessed under the same criteria.

You must not provide a Derivative Work for a Restricted Environment or bypass a Software Restriction
to make it operate there, regardless of whether it would otherwise be in Direct Competition.

### 4.5 Same Terms
You must distribute a Derivative Work only under these terms, and must not offer or impose
additional or different terms that restrict the rights they grant. By distributing it, You grant
everyone who receives it the same licenses for Your changes that these terms grant for the Software.
Obligations owed to the Licensor under these terms remain owed only to the Licensor. You may add a
copyright notice for Your changes, and recipients must keep it along with these terms and the
[Required Notice](#required-notice).

## 5. Patent License
The Licensor grants You a patent license for the Software that covers patent claims the
Licensor can license, or becomes able to license, that You would infringe by using the
Software.

## 6. Scope of License
These terms do not allow You to sublicense or transfer any of Your licenses to anyone
else, or prevent the Licensor from granting licenses to anyone else. These terms do not
imply any other licenses.

### 6.1 Fair Use
You may have "fair use" rights for the Software under the law. These terms do not limit
them.

## 7. Violations
The first time You are notified in writing that You have violated any of these terms, or
done anything with the Software not covered by Your licenses, Your licenses can
nonetheless continue if You come into full compliance with these terms, and take
practical steps to correct past violations, within 32 days of receiving notice.
Otherwise, all Your licenses end immediately.

## 8. Disclaimer of Warranties and Limitation of Liability
***As far as the law allows, the Software comes as is, without any warranty or
condition, and the Licensor will not be liable to You for any damages arising out of
these terms or the use or nature of the Software, under any kind of legal claim.***