package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Setting;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The company's logo setting: uploaded as a PNG under a new key, shown at an address that changes with it, removed. */
class CompanyLogoServiceTest {

    private SettingRepository settingRepo;
    private FileStorageService storage;
    private CompanyLogoService service;
    private Setting row;

    @BeforeEach
    void setUp() {
        settingRepo = mock(SettingRepository.class);
        storage = mock(FileStorageService.class);
        row = new Setting();
        row.setSettingKey(SettingKey.COMPANY_LOGO.key());
        when(settingRepo.findBySettingKey("company.logo")).thenReturn(Optional.of(row));
        service = new CompanyLogoService(settingRepo, storage);
    }

    @Test
    void noLogoMeansNoAddressAndNothingInThePdf() {
        assertThat(service.url()).isNull();
        assertThat(service.dataUri()).isNull();
        assertThat(service.png()).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void anUploadIsKeptAsAPngUnderANewKeyAndShown() throws IOException {
        String key = service.upload(jpeg(400, 200));

        assertThat(key).matches("company/logo-[0-9a-f-]{36}\\.png");
        assertThat(row.getSettingValue()).isEqualTo(key);
        verify(storage).put(eq(key), argThat(b -> "image/png".equals(Documents.detect(b))), eq("image/png"));
        assertThat(service.url()).startsWith("/settings/logo?v=");
        assertThat(service.dataUri()).startsWith("data:image/png;base64,");
        verify(storage, never()).open(any()); // the bytes just stored are kept in memory

        String first = service.url();
        service.upload(jpeg(400, 200));
        assertThat(service.url()).isNotEqualTo(first); // a new logo, a new address: no browser keeps the old one
    }

    @Test
    void theStoredLogoIsReadOnceThenKept() throws IOException {
        row.setSettingValue("company/logo-a.png");
        byte[] png = jpeg(10, 10);
        when(storage.open("company/logo-a.png")).thenAnswer(i -> new ByteArrayInputStream(png));

        assertThat(service.png()).contains(png);
        assertThat(service.png()).contains(png);
        verify(storage, times(1)).open("company/logo-a.png");
    }

    @Test
    void storageDownPrintsWithoutTheLogo() {
        row.setSettingValue("company/logo-a.png");
        when(storage.open(any())).thenThrow(BusinessException.of("file.storageDown"));

        assertThat(service.png()).isEmpty();
        assertThat(service.dataUri()).isNull();
    }

    @Test
    void anEmptyOrForeignFileIsNeverStored() {
        assertThatThrownBy(() -> service.upload(new byte[0]))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("settings.logo.empty"));
        assertThatThrownBy(() -> service.upload("%PDF-1.7".getBytes()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("settings.logo.type"));
        verifyNoInteractions(storage);
        assertThat(row.getSettingValue()).isNull();
    }

    @Test
    void removingClearsTheSettingOnce() {
        row.setSettingValue("company/logo-a.png");

        assertThat(service.remove()).isTrue();
        assertThat(row.getSettingValue()).isNull();
        assertThat(service.remove()).isFalse();
        assertThat(service.url()).isNull();
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }
}
