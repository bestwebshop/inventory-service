package tech.bestwebshop.api.productcomposite.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.validation.constraints.NotEmpty;

@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class CategoryDTO {
    @NotEmpty
    @NonNull
    private String name;
}
