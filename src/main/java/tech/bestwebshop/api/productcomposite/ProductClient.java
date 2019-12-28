package tech.bestwebshop.api.productcomposite;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.hystrix.EnableHystrix;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@EnableHystrix
@RestController
public class ProductClient {
    private final Map<Long, Product> productCache = new LinkedHashMap<Long, Product>();

    @Autowired
    private RestTemplate restTemplate;

    @HystrixCommand(fallbackMethod = "getProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/products/{id}")
    public Product getProduct(@PathVariable(value = "id") Long productId) {
        ProductCore tmpProductCore = restTemplate.getForObject("http://product-service/product/" + productId, ProductCore.class);
        System.out.println("Found product: " + tmpProductCore);
        CategoryCore tmpCategoryCore = restTemplate.getForObject("http://category-service/category/" + tmpProductCore.getCategoryID(), CategoryCore.class);
        System.out.println("Found category: " + tmpCategoryCore);
        Product tmpProduct = new Product(tmpProductCore.getId(), tmpProductCore.getName(), tmpProductCore.getPrice(),
                tmpCategoryCore.getName(), tmpProductCore.getDetails());
        productCache.putIfAbsent(productId, tmpProduct);
        return tmpProduct;
    }

    public Product getProductCache(Long productId){
        return productCache.getOrDefault(productId, new Product());
    }

   /* @PostMapping("/products")
    public Product createProduct(@RequestBody @Valid Product product){
        ProductCore productCore = new ProductCore(product.getName(), product.getPrice(), product.getCategory().get)

        return product;
    }*/
}
