package tech.bestwebshop.api.productcomposite;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.hystrix.EnableHystrix;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@Component
@EnableHystrix
@RestController
@RequestMapping("/")
public class ProductClient {
    private final Map<Long, Product> productCache = new LinkedHashMap<Long, Product>();

    private static final String PRODUCT_SERVICE_URL = "http://product-service:8080/products";
    private static final String CATEGORY_SERVICE_URL = "http://category-service:8080/categories";

    @RequestMapping("/")
    public void catchAll() {
        System.out.println("#### Landed in Catch-all");
    }

    @GetMapping("/inventory-api")
    public String catchInventory() {
        System.out.println("#### Landed in /inventory-api");
        return "Success";
    }

    @Autowired
    private RestTemplate restTemplate;

    @HystrixCommand(fallbackMethod = "getProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/products/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable(value = "id") Long productId) {
        ResponseEntity<ProductCore> coreProductEntity;
        try {
            coreProductEntity = restTemplate.exchange(PRODUCT_SERVICE_URL + "/" + productId,
                    HttpMethod.GET, buildHttpEntity(), ProductCore.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
        ProductCore tmpProductCore = requireNonNull(coreProductEntity.getBody());
        System.out.println("Found product: " + tmpProductCore);

        ResponseEntity<CategoryCore> coreCategoryEntity;
        try {
            coreCategoryEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + tmpProductCore.getCategoryID(),
                    HttpMethod.GET, buildHttpEntity(), CategoryCore.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }

        CategoryCore tmpCategoryCore = requireNonNull(coreCategoryEntity.getBody());
        System.out.println("Found category: " + tmpCategoryCore);

        Product tmpProduct = new Product(tmpProductCore.getId(), tmpProductCore.getName(), tmpProductCore.getPrice(),
                tmpCategoryCore, tmpProductCore.getDetails());
        productCache.putIfAbsent(productId, tmpProduct);
        return ResponseEntity.ok(tmpProduct);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> getProductCache(Long productId) {
        Product product = productCache.get(productId);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product);
    }

   /* @PostMapping("/products")
    public Product createProduct(@RequestBody @Valid Product product){
        ProductCore productCore = new ProductCore(product.getName(), product.getPrice(), product.getCategory().get)

        return product;
    }*/

    /*private HttpEntity buildHttpEntity(OAuth2Authentication auth) {
        return buildHttpEntity(auth, null);
    }

    private <T> HttpEntity<T> buildHttpEntity(OAuth2Authentication auth, @Nullable T body) {
        final OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails) auth.getDetails();
        LOGGER.info("Token is: " + details.getTokenValue());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(details.getTokenValue());
        return new HttpEntity<>(body, headers);
    }*/

    private <T> HttpEntity<T> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        return new HttpEntity<>(null, headers);
    }

}
