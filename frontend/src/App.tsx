import { Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/RequireAuth';
import { Layout } from './components/Layout';
import { AdminCatalogPage } from './pages/admin/AdminCatalogPage';
import { AdminReportsPage } from './pages/admin/AdminReportsPage';
import { CartPage } from './pages/CartPage';
import { CatalogPage } from './pages/CatalogPage';
import { LibraryPage } from './pages/LibraryPage';
import { LoginPage } from './pages/LoginPage';
import { MyReviewsPage } from './pages/MyReviewsPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { OrdersPage } from './pages/OrdersPage';
import { ProductPage } from './pages/ProductPage';
import { ProfilePage } from './pages/ProfilePage';
import { RegisterPage } from './pages/RegisterPage';
import { WishlistPage } from './pages/WishlistPage';

/** Rapor §menü tablosu: rol bazlı ekranlar. */
export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<CatalogPage />} />
        <Route path="urun/:id" element={<ProductPage />} />
        <Route path="giris" element={<LoginPage />} />
        <Route path="kayit" element={<RegisterPage />} />

        <Route path="sepet" element={<RequireAuth roles={['CUSTOMER']}><CartPage /></RequireAuth>} />
        <Route path="siparisler" element={<RequireAuth roles={['CUSTOMER']}><OrdersPage /></RequireAuth>} />
        <Route path="siparisler/:id" element={<RequireAuth roles={['CUSTOMER']}><OrderDetailPage /></RequireAuth>} />
        <Route path="kutuphane" element={<RequireAuth roles={['CUSTOMER']}><LibraryPage /></RequireAuth>} />
        <Route path="istek-listesi" element={<RequireAuth roles={['CUSTOMER']}><WishlistPage /></RequireAuth>} />
        <Route path="yorumlarim" element={<RequireAuth roles={['CUSTOMER']}><MyReviewsPage /></RequireAuth>} />
        <Route path="profil" element={<RequireAuth><ProfilePage /></RequireAuth>} />

        <Route path="admin/katalog" element={<RequireAuth roles={['ADMIN']}><AdminCatalogPage /></RequireAuth>} />
        <Route path="admin/raporlar" element={<RequireAuth roles={['ADMIN']}><AdminReportsPage /></RequireAuth>} />

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
