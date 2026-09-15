package quest.server.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces Spring Boot's `JpaTransactionManager` (which backs off on any `TransactionManager` bean) with the
 * tenant-aware one, so the `school` filter is enabled on every transaction of a scoped request.
 */
@Configuration
public class TenancyConfig {
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf, TenantContext tenant, ObjectProvider<TransactionManagerCustomizers> customizers) {
        var tm = new TenantTransactionManager(emf, tenant);
        customizers.ifAvailable(c -> c.customize(tm));
        return tm;
    }
}
