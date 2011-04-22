import org.powertac.common.PluginConfig

class AccountingServiceBootStrap
{
  def accountingService
  def tariffMarketService
  
  def init = { servletContext ->
    // create and configure PluginConfig instances for the two services
    PluginConfig accounting = 
        new PluginConfig(roleName:'Accounting',
          configuration: [bankInterest: '0.0'])
    accounting.save()
    accountingService.configuration = accounting
    
    PluginConfig tariffMarket =
        new PluginConfig(roleName: 'TariffMarket',
          configuration: [tariffPublicationFee: '100.0',
                          tariffRevocationFee: '100.0',
                          publicationInterval: '6'])
    tariffMarket.save()
    tariffMarketService.configuration = tariffMarket
  }
}
