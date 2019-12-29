package tech.bestwebshop.api.productcomposite;

import lombok.*;

import java.io.Serializable;

@Data
@RequiredArgsConstructor
@NoArgsConstructor
public class ProductCore implements Serializable {

    @NonNull
    private int id;
    @NonNull
    private String name;
    @NonNull
    private double price;
    @NonNull
    private int categoryID;
    @NonNull
    private String details;
}
